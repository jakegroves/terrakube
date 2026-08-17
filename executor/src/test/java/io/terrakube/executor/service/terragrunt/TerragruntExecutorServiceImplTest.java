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
        File terraformBinary = new File(tempDir.toFile(), "terraform");
        FileUtils.writeStringToFile(terraformBinary, "", java.nio.charset.Charset.defaultCharset());

        File fakeTerragrunt = new File(tempDir.toFile(), "terragrunt.sh");
        FileUtils.writeStringToFile(fakeTerragrunt,
                "#!/bin/sh\necho \"TF_PATH=$TG_TF_PATH\"\necho \"ARGS=$@\"\nexit 0\n",
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
    }
}
