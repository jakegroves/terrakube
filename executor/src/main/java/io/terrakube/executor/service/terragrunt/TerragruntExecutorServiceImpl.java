package io.terrakube.executor.service.terragrunt;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.terrakube.executor.plugin.tfstate.TerraformState;
import io.terrakube.executor.service.executor.ExecutorJobResult;
import io.terrakube.executor.service.logs.LogsConsumer;
import io.terrakube.executor.service.logs.ProcessLogs;
import io.terrakube.executor.service.mode.TerraformJob;
import io.terrakube.executor.service.scripts.ScriptEngineService;
import io.terrakube.executor.service.scripts.bash.ProcessLauncher;
import io.terrakube.executor.service.terraform.ApplyStructuredOutputService;
import io.terrakube.executor.service.terraform.PlanStructuredOutputService;
import io.terrakube.executor.service.terraform.TerraformExecutor;
import io.terrakube.executor.service.terraform.TerraformJsonEventParser;
import io.terrakube.executor.service.terraform.TerraformOutputsService;
import io.terrakube.terraform.TerraformClient;
import io.terrakube.terraform.TerraformDownloader;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.text.TextStringBuilder;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Terragrunt counterpart to {@code TerraformExecutorServiceImpl}. The external
 * {@code terraform-spring-boot-starter} library only knows how to run
 * terraform/tofu directly, so this class shells out to the {@code terragrunt}
 * binary itself (via {@link ProcessLauncher}) and reuses every other reusable
 * piece already in this module: live JSON-line parsing
 * ({@link TerraformJsonEventParser}), the structured plan/apply panel
 * ({@link PlanStructuredOutputService}/{@link ApplyStructuredOutputService}),
 * plan-file persistence ({@link TerraformState#saveTerraformPlan}/
 * {@link TerraformState#downloadTerraformPlan}, unchanged - Terragrunt is told
 * to write/read the plan file at the same fixed path those already expect),
 * and job logging ({@link ProcessLogs}).
 *
 * <p>Every terraform-aware operation (init/plan/apply/destroy/show/state
 * pull/output) is run <em>through terragrunt</em>, never through the plain
 * terraform/tofu client - terragrunt resolves its own cache directory and
 * provider context, so mixing in a plain `terraform show` against the
 * checkout root (as the terraform/tofu path does) is not reliable here.
 *
 * <p>Known v1 gaps, called out rather than silently skipped: no SSH module
 * key / dynamic-credential env rewiring (ported from
 * {@code TerraformExecutorServiceImpl} only for the terraform/tofu path so
 * far), and Terrakube's own backend override file is always written
 * ({@link TerraformState#getBackendStateFile}) - if a repository's
 * {@code terragrunt.hcl} also declares its own {@code remote_state} block,
 * the two will conflict. Both need verification against a real
 * terragrunt.hcl-based repository before this ships.
 */
@Slf4j
@Service("terragruntExecutor")
public class TerragruntExecutorServiceImpl implements TerraformExecutor {

    private static final String STEP_SEPARATOR = "***************************************";
    private static final long PROGRESS_FLUSH_INTERVAL_MS = 1000;
    private static final String PLAN_FILE_NAME = "terraformLibrary.tfPlan";

    private final TerraformClient terraformClient;
    private final TerraformState terraformState;
    private final ScriptEngineService scriptEngineService;
    private final ProcessLogs logsService;
    private final PlanStructuredOutputService planStructuredOutputService;
    private final ApplyStructuredOutputService applyStructuredOutputService;
    private final TerraformOutputsService terraformOutputsService;
    private final ObjectMapper objectMapper;
    private final RedisTemplate redisTemplate;
    private final TerragruntBinaryResolver terragruntBinaryResolver;
    private final ExecutorService processExecutor = Executors.newWorkStealingPool();

    public TerragruntExecutorServiceImpl(TerraformClient terraformClient,
                                          TerraformState terraformState,
                                          ScriptEngineService scriptEngineService,
                                          ProcessLogs logsService,
                                          PlanStructuredOutputService planStructuredOutputService,
                                          ApplyStructuredOutputService applyStructuredOutputService,
                                          TerraformOutputsService terraformOutputsService,
                                          ObjectMapper objectMapper,
                                          RedisTemplate redisTemplate,
                                          TerragruntBinaryResolver terragruntBinaryResolver) {
        this.terraformClient = terraformClient;
        this.terraformState = terraformState;
        this.scriptEngineService = scriptEngineService;
        this.logsService = logsService;
        this.planStructuredOutputService = planStructuredOutputService;
        this.applyStructuredOutputService = applyStructuredOutputService;
        this.terraformOutputsService = terraformOutputsService;
        this.objectMapper = objectMapper;
        this.redisTemplate = redisTemplate;
        this.terragruntBinaryResolver = terragruntBinaryResolver;
    }

    @Override
    public ExecutorJobResult plan(TerraformJob terraformJob, File executorTempDirectory, boolean isDestroy) {
        logsService.setupConsumerGroups(terraformJob.getJobId());
        ExecutorJobResult result;

        TextStringBuilder jobOutput = new TextStringBuilder();
        TextStringBuilder jobErrorOutput = new TextStringBuilder();
        try {
            File terraformWorkingDir = getTerraformWorkingDir(terraformJob, executorTempDirectory);
            Consumer<String> planOutput = LogsConsumer.builder()
                    .jobId(Integer.valueOf(terraformJob.getJobId()))
                    .terraformOutput(jobOutput)
                    .stepId(terraformJob.getStepId())
                    .processLogs(logsService)
                    .lineNumber(new AtomicInteger(0))
                    .build();

            boolean initSuccessful = prepareTerragruntOperation(terraformJob, terraformWorkingDir, planOutput);

            boolean executionPlan = false;
            boolean planCommandExecuted = false;
            int exitCode = 0;

            if (initSuccessful) {
                boolean scriptBeforeSuccessPlan = executePreOperationScripts(terraformJob, terraformWorkingDir, planOutput);

                showTerragruntMessage(terraformJob, "PLAN", planOutput);

                if (scriptBeforeSuccessPlan) {
                    planCommandExecuted = true;

                    File planFile = new File(terraformWorkingDir, PLAN_FILE_NAME);
                    List<String> args = new ArrayList<>(List.of("plan", "-input=false", "-json",
                            "-out=" + planFile.getAbsolutePath()));
                    if (isDestroy) {
                        args.add("-destroy");
                    }

                    List<Map<String, Object>> liveChanges = new ArrayList<>();
                    List<Map<String, Object>> jobDiagnostics = new ArrayList<>();
                    exitCode = runTerragruntJson(terraformJob, terraformWorkingDir, args, planOutput,
                            liveChanges, jobDiagnostics, "plan");

                    terraformJob.setLiveChanges(liveChanges);
                    terraformJob.setJobDiagnostics(jobDiagnostics);
                } else {
                    exitCode = 1;
                    executeOnFailureOperationScripts(terraformJob, terraformWorkingDir, planOutput);
                }
            } else {
                exitCode = 1;
                executeOnFailureOperationScripts(terraformJob, terraformWorkingDir, planOutput);
            }

            if (planCommandExecuted && (exitCode != 1 || terraformJob.isIgnoreError())) {
                executionPlan = true;
            } else if (planCommandExecuted) {
                executeOnFailureOperationScripts(terraformJob, terraformWorkingDir, planOutput);
            }

            log.warn("Terragrunt plan Executed: {} Exit Code: {}", executionPlan, exitCode);

            boolean scriptAfterSuccessPlan = executePostOperationScripts(terraformJob, terraformWorkingDir, planOutput, executionPlan);

            waitForStreamCompletion(terraformJob.getJobId(), 300);

            result = generateJobResult(scriptAfterSuccessPlan, jobOutput.toString(), jobErrorOutput.toString());
            result.setPlanFile(executionPlan ? terraformState.saveTerraformPlan(terraformJob.getOrganizationId(),
                    terraformJob.getWorkspaceId(), terraformJob.getJobId(), terraformJob.getStepId(), terraformWorkingDir)
                    : "");
            if (executionPlan) {
                planStructuredOutputService.publishPlanSummary(terraformJob,
                        getShowJson(terraformJob, terraformWorkingDir, new File(terraformWorkingDir, PLAN_FILE_NAME)),
                        terraformJob.getLiveChanges(), terraformJob.getJobDiagnostics());
            }
            result.setPlan(true);
            result.setExitCode(exitCode);
        } catch (IOException | ExecutionException | InterruptedException exception) {
            result = setError(exception);
            result.setExitCode(1);
        }
        return result;
    }

    @Override
    public ExecutorJobResult apply(TerraformJob terraformJob, File executorTempDirectory) {
        logsService.setupConsumerGroups(terraformJob.getJobId());
        ExecutorJobResult result;

        TextStringBuilder terraformOutput = new TextStringBuilder();
        TextStringBuilder terraformErrorOutput = new TextStringBuilder();
        try {
            File terraformWorkingDir = getTerraformWorkingDir(terraformJob, executorTempDirectory);
            Consumer<String> applyOutput = LogsConsumer.builder()
                    .jobId(Integer.valueOf(terraformJob.getJobId()))
                    .lineNumber(new AtomicInteger(0))
                    .terraformOutput(terraformOutput)
                    .stepId(terraformJob.getStepId())
                    .processLogs(logsService)
                    .build();

            boolean execution = false;
            boolean initSuccessful = prepareTerragruntOperation(terraformJob, terraformWorkingDir, applyOutput);

            if (initSuccessful) {
                boolean scriptBeforeSuccess = executePreOperationScripts(terraformJob, terraformWorkingDir, applyOutput);

                showTerragruntMessage(terraformJob, "APPLY", applyOutput);

                if (scriptBeforeSuccess) {
                    File planFile = new File(terraformWorkingDir, PLAN_FILE_NAME);
                    boolean planFileDownloaded = terraformState.downloadTerraformPlan(terraformJob.getOrganizationId(),
                            terraformJob.getWorkspaceId(), terraformJob.getJobId(), terraformJob.getStepId(),
                            terraformWorkingDir);

                    List<String> args = new ArrayList<>(List.of("apply", "-input=false", "-json"));
                    if (planFileDownloaded) {
                        args.add(planFile.getAbsolutePath());
                    } else {
                        args.add("-auto-approve");
                    }

                    List<Map<String, Object>> changes = applyStructuredOutputService.seedFromPlan(
                            terraformJob.getOrganizationId(), terraformJob.getJobId());
                    List<Map<String, Object>> jobDiagnostics = new ArrayList<>();
                    if (!changes.isEmpty()) {
                        applyStructuredOutputService.publishApplyProgress(
                                terraformJob.getOrganizationId(), terraformJob.getJobId(), terraformJob.getStepId(), changes, jobDiagnostics);
                    }

                    int exitCode = runTerragruntJson(terraformJob, terraformWorkingDir, args, applyOutput,
                            changes, jobDiagnostics, "apply");
                    execution = exitCode == 0;

                    handleTerragruntStateChange(terraformJob, terraformWorkingDir);

                    String stateJson = getShowStateJson(terraformJob, terraformWorkingDir);
                    if (stateJson != null) {
                        applyStructuredOutputService.resolveFinalValues(changes, stateJson);
                        applyStructuredOutputService.publishApplyProgress(
                                terraformJob.getOrganizationId(), terraformJob.getJobId(), terraformJob.getStepId(), changes, jobDiagnostics);
                    }
                }
            }

            if (!execution) {
                executeOnFailureOperationScripts(terraformJob, terraformWorkingDir, applyOutput);
            }

            log.warn("Terragrunt apply Executed Successfully: {}", execution);
            boolean scriptAfterSuccess = executePostOperationScripts(terraformJob, terraformWorkingDir, applyOutput, execution || terraformJob.isIgnoreError());

            waitForStreamCompletion(terraformJob.getJobId(), 300);
            result = generateJobResult(scriptAfterSuccess, terraformOutput.toString(), terraformErrorOutput.toString());
        } catch (IOException | ExecutionException | InterruptedException exception) {
            result = setError(exception);
        }
        return result;
    }

    @Override
    public ExecutorJobResult destroy(TerraformJob terraformJob, File executorTempDirectory) {
        logsService.setupConsumerGroups(terraformJob.getJobId());
        ExecutorJobResult result;

        TextStringBuilder jobOutput = new TextStringBuilder();
        TextStringBuilder jobErrorOutput = new TextStringBuilder();
        try {
            File terraformWorkingDir = getTerraformWorkingDir(terraformJob, executorTempDirectory);
            Consumer<String> outputDestroy = LogsConsumer.builder()
                    .jobId(Integer.valueOf(terraformJob.getJobId()))
                    .terraformOutput(jobOutput)
                    .stepId(terraformJob.getStepId())
                    .processLogs(logsService)
                    .lineNumber(new AtomicInteger(0))
                    .build();

            boolean execution = false;
            boolean initSuccessful = prepareTerragruntOperation(terraformJob, terraformWorkingDir, outputDestroy);

            if (initSuccessful) {
                boolean scriptBeforeSuccess = executePreOperationScripts(terraformJob, terraformWorkingDir, outputDestroy);

                showTerragruntMessage(terraformJob, "DESTROY", outputDestroy);

                if (scriptBeforeSuccess) {
                    List<String> args = List.of("destroy", "-input=false", "-auto-approve", "-json");
                    List<Map<String, Object>> changes = new ArrayList<>();
                    List<Map<String, Object>> jobDiagnostics = new ArrayList<>();

                    int exitCode = runTerragruntJson(terraformJob, terraformWorkingDir, args, outputDestroy,
                            changes, jobDiagnostics, "apply");
                    execution = exitCode == 0;

                    handleTerragruntStateChange(terraformJob, terraformWorkingDir);
                }
            }

            if (!execution) {
                executeOnFailureOperationScripts(terraformJob, terraformWorkingDir, outputDestroy);
            }

            log.warn("Terragrunt destroy Executed Successfully: {}", execution);
            boolean scriptAfterSuccess = executePostOperationScripts(terraformJob, terraformWorkingDir, outputDestroy, execution);

            waitForStreamCompletion(terraformJob.getJobId(), 300);
            result = generateJobResult(scriptAfterSuccess, jobOutput.toString(), jobErrorOutput.toString());
        } catch (IOException | ExecutionException | InterruptedException exception) {
            result = setError(exception);
        }
        return result;
    }

    @Override
    public String version() {
        try {
            String version = TerragruntBinaryResolver.DEFAULT_TERRAGRUNT_VERSION;
            File terragruntBinary = terragruntBinaryResolver.ensureBinary(version);
            TextStringBuilder output = new TextStringBuilder();
            ProcessLauncher processLauncher = new ProcessLauncher(processExecutor, terragruntBinary.getAbsolutePath(), "--version");
            processLauncher.setOutputListener(output::appendln);
            processLauncher.setErrorListener(output::appendln);
            processLauncher.launch().get();
            return output.toString().trim();
        } catch (Exception exception) {
            log.error("Unable to resolve terragrunt version: {}", exception.getMessage());
            return "";
        }
    }

    // ---------------------------------------------------------------------
    // Terragrunt process invocation
    // ---------------------------------------------------------------------

    private boolean prepareTerragruntOperation(TerraformJob terraformJob, File terraformWorkingDir, Consumer<String> output)
            throws IOException, ExecutionException, InterruptedException {
        if (!executePreInitScripts(terraformJob, terraformWorkingDir, output)) {
            log.warn("Skipping terragrunt init because before-init scripts failed for Job {}", terraformJob.getJobId());
            return false;
        }

        return executeTerragruntInit(terraformJob, terraformWorkingDir, output);
    }

    private boolean executeTerragruntInit(TerraformJob terraformJob, File terraformWorkingDir, Consumer<String> output)
            throws IOException, ExecutionException, InterruptedException {
        if (terraformJob.isShowHeader()) {
            initBanner(terraformJob, output);
        }

        terraformState.getBackendStateFile(terraformJob.getOrganizationId(), terraformJob.getWorkspaceId(),
                terraformWorkingDir, terraformJob.getTerraformVersion());

        File terraformBinary = ensureTerraformBinary(terraformJob);
        File terragruntBinary = terragruntBinaryResolver.ensureBinary(terragruntBinaryResolver.resolveVersion(terraformJob));

        int exitCode = runTerragrunt(terraformJob, terraformWorkingDir, terragruntBinary, terraformBinary,
                List.of("init", "-input=false"), output, output);

        Thread.sleep(5000);
        return exitCode == 0;
    }

    /**
     * Runs a terragrunt subcommand whose stdout is a `-json` event stream, feeding each line
     * through {@link TerraformJsonEventParser} the same way plan()/apply() do in
     * TerraformExecutorServiceImpl, and periodically publishing progress to the structured
     * plan/apply panel.
     */
    private int runTerragruntJson(TerraformJob terraformJob, File terraformWorkingDir, List<String> args,
                                   Consumer<String> rawOutput, List<Map<String, Object>> changes,
                                   List<Map<String, Object>> jobDiagnostics, String phase)
            throws IOException, ExecutionException, InterruptedException {
        File terraformBinary = ensureTerraformBinary(terraformJob);
        File terragruntBinary = terragruntBinaryResolver.ensureBinary(terragruntBinaryResolver.resolveVersion(terraformJob));

        TerraformJsonEventParser eventParser = new TerraformJsonEventParser(objectMapper);
        AtomicLong lastFlush = new AtomicLong(0);

        Consumer<String> jsonLineConsumer = (line) -> {
            String humanMessage = eventParser.parseLine(line, changes, jobDiagnostics);
            if (humanMessage != null) {
                rawOutput.accept(humanMessage);
            }

            long now = System.currentTimeMillis();
            if (now - lastFlush.get() > PROGRESS_FLUSH_INTERVAL_MS) {
                lastFlush.set(now);
                publishProgress(phase, terraformJob, changes, jobDiagnostics);
            }
        };

        int exitCode = runTerragrunt(terraformJob, terraformWorkingDir, terragruntBinary, terraformBinary,
                args, jsonLineConsumer, rawOutput);

        publishProgress(phase, terraformJob, changes, jobDiagnostics);
        return exitCode;
    }

    private void publishProgress(String phase, TerraformJob terraformJob, List<Map<String, Object>> changes,
                                  List<Map<String, Object>> jobDiagnostics) {
        if ("plan".equals(phase)) {
            planStructuredOutputService.publishPlanProgress(terraformJob.getOrganizationId(), terraformJob.getJobId(),
                    terraformJob.getStepId(), changes, jobDiagnostics);
        } else {
            applyStructuredOutputService.publishApplyProgress(terraformJob.getOrganizationId(), terraformJob.getJobId(),
                    terraformJob.getStepId(), changes, jobDiagnostics);
        }
        pushLiveStructuredUpdate(phase, terraformJob, changes, jobDiagnostics);
    }

    private int runTerragrunt(TerraformJob terraformJob, File terraformWorkingDir, File terragruntBinary,
                               File terraformBinary, List<String> args, Consumer<String> outputListener,
                               Consumer<String> errorListener) throws ExecutionException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(terragruntBinary.getAbsolutePath());
        command.addAll(args);

        ProcessLauncher processLauncher = new ProcessLauncher(processExecutor, command.toArray(new String[0]));
        processLauncher.setDirectory(terraformWorkingDir);
        processLauncher.setEnvironmentVariable("TF_IN_AUTOMATION", "true");
        // TG_TF_PATH/TG_NON_INTERACTIVE are the current (0.6x+) Terragrunt env var names for
        // "which terraform/tofu binary to drive" and "never prompt". Older Terragrunt releases
        // used TERRAGRUNT_TFPATH/--terragrunt-non-interactive instead - since the resolved
        // terragrunt binary version is configurable per workspace, confirm these still match
        // whatever version ends up pinned/tested against a real terragrunt.hcl repo.
        processLauncher.setEnvironmentVariable("TG_TF_PATH", terraformBinary.getAbsolutePath());
        processLauncher.setEnvironmentVariable("TG_NON_INTERACTIVE", "true");
        processLauncher.setOrAppendEnvironmentVariable("PATH", terraformBinary.getParentFile().getAbsolutePath(), File.pathSeparator);
        processLauncher.setOrAppendEnvironmentVariable("PATH", terragruntBinary.getParentFile().getAbsolutePath(), File.pathSeparator);

        HashMap<String, String> variables = terraformJob.getVariables();
        if (variables != null) {
            variables.forEach((key, value) -> processLauncher.setEnvironmentVariable("TF_VAR_" + key, value));
        }
        HashMap<String, String> environmentVariables = terraformJob.getEnvironmentVariables();
        if (environmentVariables != null) {
            environmentVariables.forEach(processLauncher::setEnvironmentVariable);
        }

        processLauncher.setOutputListener(outputListener);
        processLauncher.setErrorListener(errorListener);

        return processLauncher.launch().get();
    }

    /** Captures the full stdout of a non-streaming terragrunt command (e.g. `show`, `output`) as one string. */
    private String captureTerragrunt(TerraformJob terraformJob, File terraformWorkingDir, List<String> args) {
        try {
            File terraformBinary = ensureTerraformBinary(terraformJob);
            File terragruntBinary = terragruntBinaryResolver.ensureBinary(terragruntBinaryResolver.resolveVersion(terraformJob));

            TextStringBuilder output = new TextStringBuilder();
            TextStringBuilder errorOutput = new TextStringBuilder();
            int exitCode = runTerragrunt(terraformJob, terraformWorkingDir, terragruntBinary, terraformBinary,
                    args, output::appendln, errorOutput::appendln);

            if (exitCode != 0) {
                log.warn("terragrunt {} failed for job {} step {}: {}", args, terraformJob.getJobId(),
                        terraformJob.getStepId(), errorOutput);
                return null;
            }

            return output.toString();
        } catch (Exception e) {
            log.warn("Unable to run terragrunt {} for job {}: {}", args, terraformJob.getJobId(), e.getMessage());
            return null;
        }
    }

    private String getShowJson(TerraformJob terraformJob, File terraformWorkingDir, File planFile) {
        return captureTerragrunt(terraformJob, terraformWorkingDir, List.of("show", "-json", planFile.getAbsolutePath()));
    }

    private String getShowStateJson(TerraformJob terraformJob, File terraformWorkingDir) {
        return captureTerragrunt(terraformJob, terraformWorkingDir, List.of("show", "-json"));
    }

    private void handleTerragruntStateChange(TerraformJob terraformJob, File terraformWorkingDir) {
        String rawState = captureTerragrunt(terraformJob, terraformWorkingDir, List.of("state", "pull"));
        if (rawState != null) {
            terraformJob.setRawState(rawState);
        }

        String stateJson = getShowStateJson(terraformJob, terraformWorkingDir);
        if (stateJson != null) {
            terraformState.saveStateJson(terraformJob, stateJson, rawState);

            String outputJson = captureTerragrunt(terraformJob, terraformWorkingDir, List.of("output", "-json"));
            if (outputJson != null) {
                terraformJob.setTerraformOutput(outputJson);
                terraformOutputsService.publishOutputs(terraformJob.getOrganizationId(), terraformJob.getJobId(),
                        terraformJob.getStepId(), outputJson);
            }
        }
    }

    // ---------------------------------------------------------------------
    // Underlying terraform/tofu binary resolution (terragrunt still needs a
    // real terraform/tofu binary to drive - reuses the same download/cache
    // mechanism the terraform/tofu-native executor path uses)
    // ---------------------------------------------------------------------

    private File ensureTerraformBinary(TerraformJob terraformJob) throws IOException, ExecutionException, InterruptedException {
        TerraformDownloader downloader = terraformClient.createTerraformDownloader();
        boolean tofu = terraformJob.isTofu();
        String resolvedVersion = tofu
                ? downloader.resolveTofuVersion(terraformJob.getTerraformVersion())
                : downloader.resolveTerraformVersion(terraformJob.getTerraformVersion());

        String binaryPath = downloader.getTerraformBinaryPath(resolvedVersion, tofu);
        File binaryFile = new File(binaryPath);

        if (binaryFile.exists()) {
            return binaryFile;
        }

        if (terraformState.downloadTerraformBinary(resolvedVersion, tofu, binaryFile)) {
            return binaryFile;
        }

        log.info("terraform/tofu binary {} not cached anywhere, triggering download for terragrunt", resolvedVersion);
        if (tofu) {
            downloader.downloadTofuVersion(resolvedVersion);
        } else {
            downloader.downloadTerraformVersion(resolvedVersion);
        }

        if (binaryFile.exists()) {
            terraformState.saveTerraformBinary(resolvedVersion, tofu, binaryFile);
        }

        return binaryFile;
    }

    // ---------------------------------------------------------------------
    // Shared plumbing - mirrors TerraformExecutorServiceImpl's private helpers of the same shape
    // ---------------------------------------------------------------------

    private File getTerraformWorkingDir(TerraformJob terraformJob, File workingDirectory) throws IOException {
        File terraformWorkingDir = workingDirectory;
        try {
            if (!terraformJob.getBranch().equals("remote-content") || (terraformJob.getFolder() != null && !terraformJob.getFolder().split(",")[0].equals("/"))) {
                terraformWorkingDir = new File(Path.of(workingDirectory.getCanonicalPath(), terraformJob.getFolder().split(",")[0]).toString());
                if (!terraformWorkingDir.isDirectory()) {
                    throw new IOException(String.format("Terraform Working Directory not exist: {}", terraformWorkingDir.getCanonicalPath()));
                }
            }
        } catch (IOException e) {
            log.error(e.getMessage());
        }
        return terraformWorkingDir;
    }

    private void waitForStreamCompletion(String jobId, int maxWaitSeconds) {
        int pollInterval = 1000;
        int totalWait = 0;
        long lastMessageCount = -1;
        int stableCount = 0;

        while (totalWait < maxWaitSeconds * 1000) {
            try {
                Long streamLength = redisTemplate.opsForStream().size(jobId);

                if (streamLength != null) {
                    if (streamLength.equals(lastMessageCount)) {
                        stableCount++;
                        if (stableCount >= 3) {
                            break;
                        }
                    } else {
                        stableCount = 0;
                        lastMessageCount = streamLength;
                    }
                }

                Thread.sleep(pollInterval);
                totalWait += pollInterval;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private ExecutorJobResult generateJobResult(boolean scriptAfterSuccess, String jobOutput, String jobErrorOutput) {
        ExecutorJobResult jobResult = new ExecutorJobResult();
        jobResult.setSuccessfulExecution(scriptAfterSuccess);
        jobResult.setOutputLog(jobOutput);
        jobResult.setOutputErrorLog(jobErrorOutput);
        return jobResult;
    }

    private ExecutorJobResult setError(Exception exception) {
        ExecutorJobResult error = generateJobResult(false, "", exception.getMessage());
        log.error(exception.getMessage());
        if (exception instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }
        return error;
    }

    private boolean executePreOperationScripts(TerraformJob terraformJob, File workingDirectory, Consumer<String> output) {
        if (terraformJob.getCommandList() == null) {
            return true;
        }
        return scriptEngineService.execute(
                terraformJob,
                terraformJob.getCommandList().stream()
                        .filter(command -> command.isBefore() && !command.isBeforeInit())
                        .collect(Collectors.toCollection(LinkedList::new)),
                workingDirectory,
                output);
    }

    private boolean executePreInitScripts(TerraformJob terraformJob, File workingDirectory, Consumer<String> output) {
        if (terraformJob.getCommandList() == null) {
            return true;
        }
        return scriptEngineService.execute(
                terraformJob,
                terraformJob.getCommandList().stream()
                        .filter(command -> command.isBeforeInit())
                        .collect(Collectors.toCollection(LinkedList::new)),
                workingDirectory,
                output);
    }

    private boolean executePostOperationScripts(TerraformJob terraformJob, File workingDirectory, Consumer<String> output, boolean execution) {
        if (!execution) {
            return false;
        }
        if (terraformJob.getCommandList() == null) {
            return true;
        }
        return scriptEngineService.execute(
                terraformJob,
                terraformJob.getCommandList().stream()
                        .filter(command -> command.isAfter())
                        .collect(Collectors.toCollection(LinkedList::new)),
                workingDirectory,
                output);
    }

    private void executeOnFailureOperationScripts(TerraformJob terraformJob, File workingDirectory, Consumer<String> output) {
        if (terraformJob.getOnFailureList() != null) {
            scriptEngineService.execute(terraformJob, new LinkedList<>(terraformJob.getOnFailureList()), workingDirectory, output);
        }
    }

    private void pushLiveStructuredUpdate(String phase, TerraformJob terraformJob, List<Map<String, Object>> changes, List<Map<String, Object>> jobDiagnostics) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("phase", phase);
            payload.put("changes", Map.of(terraformJob.getStepId(), changes));
            payload.put("jobDiagnostics", Map.of(terraformJob.getStepId(), jobDiagnostics));
            logsService.sendStructuredUpdate(Integer.valueOf(terraformJob.getJobId()), terraformJob.getStepId(),
                    objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            log.warn("Unable to push live structured update for job {} step {}", terraformJob.getJobId(), terraformJob.getStepId(), e);
        }
    }

    private void initBanner(TerraformJob terraformJob, Consumer<String> output) {
        output.accept(STEP_SEPARATOR);
        output.accept("Initializing Terrakube Job " + terraformJob.getJobId() + " Step " + terraformJob.getStepId());
        output.accept("Running Terragrunt " + terragruntBinaryResolver.resolveVersion(terraformJob));
        output.accept("\n\n" + STEP_SEPARATOR);
        output.accept("Running Terragrunt Init: ");
    }

    private void showTerragruntMessage(TerraformJob terraformJob, String operation, Consumer<String> output) throws InterruptedException {
        output.accept(STEP_SEPARATOR);
        output.accept("Running Terragrunt " + operation);
        output.accept(STEP_SEPARATOR);
        Thread.sleep(2000);
    }
}
