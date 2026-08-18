package io.terrakube.executor.service.terragrunt;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.terrakube.executor.plugin.tfstate.TerraformState;
import io.terrakube.executor.service.logs.ProcessLogs;
import io.terrakube.executor.service.mode.TerraformJob;
import io.terrakube.executor.service.scripts.ScriptEngineService;
import io.terrakube.executor.service.terraform.ApplyStructuredOutputService;
import io.terrakube.executor.service.terraform.PlanStructuredOutputService;
import io.terrakube.executor.service.terraform.TerraformOutputsService;
import io.terrakube.terraform.TerraformClient;
import io.terrakube.terraform.TerraformDownloader;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.redis.core.RedisTemplate;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TerragruntExecutorServiceImplTest {

    private final TerraformClient terraformClient = mock(TerraformClient.class);
    private final TerraformDownloader terraformDownloader = mock(TerraformDownloader.class);
    private final TerraformState terraformState = mock(TerraformState.class);
    private final TerragruntBinaryResolver terragruntBinaryResolver = mock(TerragruntBinaryResolver.class);
    @SuppressWarnings("unchecked")
    private final RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);

    private final TerragruntExecutorServiceImpl subject = new TerragruntExecutorServiceImpl(
            terraformClient,
            terraformState,
            mock(ScriptEngineService.class),
            mock(ProcessLogs.class),
            mock(PlanStructuredOutputService.class),
            mock(ApplyStructuredOutputService.class),
            mock(TerraformOutputsService.class),
            new ObjectMapper(),
            redisTemplate,
            terragruntBinaryResolver);

    @AfterEach
    void cleanup() {
        FileUtils.deleteQuietly(new File(FileUtils.getUserDirectoryPath(), ".terraform-spring-boot/terraform"));
        FileUtils.deleteQuietly(new File(FileUtils.getUserDirectoryPath(), ".terraform-spring-boot/tofu"));
    }

    private File invokeEnsureTerraformBinary(TerraformJob job) throws Exception {
        Method method = TerragruntExecutorServiceImpl.class.getDeclaredMethod("ensureTerraformBinary", TerraformJob.class);
        method.setAccessible(true);
        return (File) method.invoke(subject, job);
    }

    @SuppressWarnings("unchecked")
    private int invokeRunTerragrunt(TerraformJob job, File workingDir, File terragruntBinary, File terraformBinary,
                                     List<String> args, Consumer<String> out, Consumer<String> err) throws Exception {
        Method method = TerragruntExecutorServiceImpl.class.getDeclaredMethod("runTerragrunt", TerraformJob.class,
                File.class, File.class, File.class, List.class, Consumer.class, Consumer.class);
        method.setAccessible(true);
        return (int) method.invoke(subject, job, workingDir, terragruntBinary, terraformBinary, args, out, err);
    }

    @Test
    void ensureTerraformBinaryReturnsExistingLocalBinaryWithoutHittingCacheOrDownloader(@TempDir Path tempDir) throws Exception {
        when(terraformClient.createTerraformDownloader()).thenReturn(terraformDownloader);
        when(terraformDownloader.resolveTerraformVersion("1.9.0")).thenReturn("1.9.0");

        File binaryFile = new File(tempDir.toFile(), "terraform");
        FileUtils.writeStringToFile(binaryFile, "fake", java.nio.charset.Charset.defaultCharset());
        when(terraformDownloader.getTerraformBinaryPath("1.9.0", false)).thenReturn(binaryFile.getAbsolutePath());

        TerraformJob job = new TerraformJob();
        job.setTerraformVersion("1.9.0");
        job.setTofu(false);

        File result = invokeEnsureTerraformBinary(job);

        assertEquals(binaryFile, result);
        verify(terraformState, never()).downloadTerraformBinary(anyString(), anyBoolean(), any());
        verify(terraformDownloader, never()).downloadTerraformVersion(anyString());
    }

    @Test
    void ensureTerraformBinaryRestoresFromCloudCacheWhenMissingLocally(@TempDir Path tempDir) throws Exception {
        when(terraformClient.createTerraformDownloader()).thenReturn(terraformDownloader);
        when(terraformDownloader.resolveTofuVersion("1.7.0")).thenReturn("1.7.0");

        File binaryFile = new File(tempDir.toFile(), "not-there-yet/tofu");
        when(terraformDownloader.getTerraformBinaryPath("1.7.0", true)).thenReturn(binaryFile.getAbsolutePath());
        when(terraformState.downloadTerraformBinary(eq("1.7.0"), eq(true), any())).thenReturn(true);

        TerraformJob job = new TerraformJob();
        job.setTerraformVersion("1.7.0");
        job.setTofu(true);

        File result = invokeEnsureTerraformBinary(job);

        assertEquals(binaryFile, result);
        verify(terraformDownloader, never()).downloadTofuVersion(anyString());
    }

    @Test
    void versionReturnsEmptyStringInsteadOfThrowingWhenBinaryResolutionFails() {
        when(terragruntBinaryResolver.ensureBinary(TerragruntBinaryResolver.DEFAULT_TERRAGRUNT_VERSION))
                .thenThrow(new IllegalStateException("no network"));

        assertEquals("", subject.version());
    }

    @Test
    void runTerragruntWiresWorkingDirectoryEnvVarsAndCapturesStdout(@TempDir Path tempDir) throws Exception {
        // Deliberately different directories from each other (and from the working directory
        // passed to runTerragrunt below) - a real regression test for the PATH-clobbering bug
        // needs the two binaries to NOT already share a parent directory.
        File terraformBinary = new File(tempDir.toFile(), "tf-bin-dir/terraform");
        FileUtils.writeStringToFile(terraformBinary, "", java.nio.charset.Charset.defaultCharset());

        File fakeTerragrunt = new File(tempDir.toFile(), "tg-bin-dir/terragrunt.sh");
        FileUtils.writeStringToFile(fakeTerragrunt,
                "#!/bin/sh\necho \"TF_PATH=$TG_TF_PATH\"\necho \"ARGS=$@\"\necho \"PROC_PATH=$PATH\"\nexit 0\n",
                java.nio.charset.Charset.defaultCharset());
        fakeTerragrunt.setExecutable(true);

        TerraformJob job = new TerraformJob();
        job.setVariables(new java.util.HashMap<>(java.util.Map.of("instance_type", "t3.micro")));
        job.setEnvironmentVariables(new java.util.HashMap<>());

        StringBuilder output = new StringBuilder();
        int exitCode = invokeRunTerragrunt(job, tempDir.toFile(), fakeTerragrunt, terraformBinary,
                List.of("plan", "-input=false"), line -> output.append(line).append('\n'), line -> {
                });

        assertEquals(0, exitCode);
        assertTrue(output.toString().contains("TF_PATH=" + terraformBinary.getAbsolutePath()));
        assertTrue(output.toString().contains("ARGS=plan -input=false"));
        // Regression check: a prior bug called setOrAppendEnvironmentVariable("PATH", ...) twice
        // (once for the terraform binary dir, once for the terragrunt binary dir), and the second
        // call silently clobbered the first instead of chaining, since it reads the real process
        // PATH rather than what the first call had already written - PATH ended up missing the
        // terraform binary directory entirely, and terragrunt failed with
        // `exec: "terraform": executable file not found in $PATH`.
        assertTrue(output.toString().contains(terraformBinary.getParentFile().getAbsolutePath()),
                "PATH must include the terraform binary directory: " + output);
        assertTrue(output.toString().contains(fakeTerragrunt.getParentFile().getAbsolutePath()),
                "PATH must also include the terragrunt binary directory: " + output);

        File tfvarsFile = new File(tempDir.toFile(), "terrakube.auto.tfvars.json");
        assertTrue(tfvarsFile.exists(), "runTerragrunt should write an auto.tfvars.json file for the job's variables");
        assertTrue(FileUtils.readFileToString(tfvarsFile, java.nio.charset.Charset.defaultCharset())
                .contains("\"instance_type\":\"t3.micro\""));
    }

    private void invokeWriteAutoTfvarsFile(File workingDir, java.util.Map<String, String> variables) throws Exception {
        Method method = TerragruntExecutorServiceImpl.class.getDeclaredMethod("writeAutoTfvarsFile", File.class, java.util.Map.class);
        method.setAccessible(true);
        method.invoke(subject, workingDir, variables);
    }

    @Test
    void writeAutoTfvarsFileSkipsWritingWhenThereAreNoVariables(@TempDir Path tempDir) throws Exception {
        invokeWriteAutoTfvarsFile(tempDir.toFile(), null);
        invokeWriteAutoTfvarsFile(tempDir.toFile(), java.util.Map.of());

        assertTrue(tempDir.toFile().listFiles() == null || tempDir.toFile().listFiles().length == 0);
    }

    @SuppressWarnings("unchecked")
    private boolean invokeHasRealChanges(List<java.util.Map<String, Object>> changes) throws Exception {
        Method method = TerragruntExecutorServiceImpl.class.getDeclaredMethod("hasRealChanges", List.class);
        method.setAccessible(true);
        return (boolean) method.invoke(subject, changes);
    }

    private java.util.Map<String, Object> changeWithAction(String action) {
        java.util.Map<String, Object> change = new java.util.HashMap<>();
        change.put("address", "random_pet.this");
        if (action != null) {
            change.put("action", action);
        }
        return change;
    }

    @Test
    void hasRealChangesIsFalseWhenEveryChangeIsNoOpOrRead() throws Exception {
        assertEquals(false, invokeHasRealChanges(List.of()));
        assertEquals(false, invokeHasRealChanges(List.of(changeWithAction("no-op"))));
        assertEquals(false, invokeHasRealChanges(List.of(changeWithAction("read"), changeWithAction("no-op"))));
        assertEquals(false, invokeHasRealChanges(List.of(changeWithAction(null))));
    }

    @Test
    void hasRealChangesIsTrueWhenAnyChangeIsARealAction() throws Exception {
        // This is the regression case for the bug reported against a real terragrunt deployment:
        // `terragrunt plan` without -detailed-exitcode (or one that doesn't propagate it reliably)
        // returns exit code 0 even when there IS a real change (e.g. creating a resource), which
        // previously made UpdateJobStatusImpl mark the whole job "completed" and skip Apply
        // entirely - the Apply step then showed as "notExecuted" in the UI.
        assertEquals(true, invokeHasRealChanges(List.of(changeWithAction("create"))));
        assertEquals(true, invokeHasRealChanges(List.of(changeWithAction("no-op"), changeWithAction("update"))));
        assertEquals(true, invokeHasRealChanges(List.of(changeWithAction("delete"))));
    }

    private File invokeResolveTerraformExecutionDir(File workingDir) throws Exception {
        Method method = TerragruntExecutorServiceImpl.class.getDeclaredMethod("resolveTerraformExecutionDir", File.class);
        method.setAccessible(true);
        return (File) method.invoke(subject, workingDir);
    }

    @Test
    void resolveTerraformExecutionDirFallsBackToWorkingDirWhenNoCacheExists(@TempDir Path tempDir) throws Exception {
        assertEquals(tempDir.toFile(), invokeResolveTerraformExecutionDir(tempDir.toFile()));
    }

    @Test
    void resolveTerraformExecutionDirFindsTheModuleDirectoryTerragruntInitializedInsideTheCache(@TempDir Path tempDir) throws Exception {
        File moduleDir = new File(tempDir.toFile(), ".terragrunt-cache/sourceHash/downloadHash");
        FileUtils.forceMkdir(new File(moduleDir, ".terraform"));

        assertEquals(moduleDir, invokeResolveTerraformExecutionDir(tempDir.toFile()));
    }

    @Test
    void resolveTerraformExecutionDirFallsBackWhenCacheExistsButInitNeverRan(@TempDir Path tempDir) throws Exception {
        FileUtils.forceMkdir(new File(tempDir.toFile(), ".terragrunt-cache/sourceHash/downloadHash"));

        assertEquals(tempDir.toFile(), invokeResolveTerraformExecutionDir(tempDir.toFile()));
    }
}
