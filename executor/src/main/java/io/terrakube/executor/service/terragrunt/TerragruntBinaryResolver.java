package io.terrakube.executor.service.terragrunt;

import io.terrakube.executor.plugin.tfstate.TerraformState;
import io.terrakube.executor.service.mode.TerraformJob;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.net.URL;

/**
 * Resolves the terragrunt version a job should use and ensures the matching
 * binary is available locally, restoring it from cloud storage (via
 * {@link TerraformState}) or downloading it from GitHub releases when it
 * isn't already cached. Mirrors how the terraform/tofu binary is resolved
 * and cached elsewhere in this module (see
 * TerraformExecutorServiceImpl#ensureBinaryCached/#saveBinaryToCache), since
 * the terraform-spring-boot-starter library that handles that only knows
 * about terraform/tofu, not terragrunt.
 */
@Slf4j
@Service
public class TerragruntBinaryResolver {

    public static final String DEFAULT_TERRAGRUNT_VERSION = "0.67.16";

    private static final String TERRAGRUNT_DIRECTORY = "/.terraform-spring-boot/terragrunt/v";
    private static final String RELEASE_URL_TEMPLATE = "https://github.com/gruntwork-io/terragrunt/releases/download/v%s/terragrunt_%s_%s";

    private final TerraformState terraformState;

    public TerragruntBinaryResolver(TerraformState terraformState) {
        this.terraformState = terraformState;
    }

    public String resolveVersion(TerraformJob terraformJob) {
        String version = terraformJob.getTerragruntVersion();
        return (version != null && !version.isBlank()) ? version.trim() : DEFAULT_TERRAGRUNT_VERSION;
    }

    public File getBinaryFile(String version) {
        String directoryPath = FileUtils.getUserDirectoryPath()
                .concat(FilenameUtils.separatorsToSystem(TERRAGRUNT_DIRECTORY + version));
        return new File(directoryPath, "terragrunt");
    }

    /**
     * Ensures the terragrunt binary for the given version exists locally,
     * restoring it from cloud storage or downloading it from GitHub if needed.
     *
     * @return the local terragrunt binary file, ready to execute
     */
    public File ensureBinary(String version) {
        File binaryFile = getBinaryFile(version);

        if (binaryFile.exists()) {
            log.info("terragrunt binary {} already present locally at {}", version, binaryFile.getAbsolutePath());
            return binaryFile;
        }

        if (terraformState.downloadTerragruntBinary(version, binaryFile)) {
            return binaryFile;
        }

        downloadFromGitHub(version, binaryFile);
        terraformState.saveTerragruntBinary(version, binaryFile);
        return binaryFile;
    }

    private void downloadFromGitHub(String version, File targetFile) {
        String url = String.format(RELEASE_URL_TEMPLATE, version, resolveOs(), resolveArch());
        log.info("Downloading terragrunt {} from {}", version, url);
        try {
            File parentDir = targetFile.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                FileUtils.forceMkdir(parentDir);
            }
            FileUtils.copyURLToFile(new URL(url), targetFile, 10_000, 60_000);
            if (!targetFile.setExecutable(true, false)) {
                log.warn("Failed to set executable permission on downloaded terragrunt binary");
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to download terragrunt " + version + " from " + url, e);
        }
    }

    private String resolveOs() {
        String osName = System.getProperty("os.name", "").toLowerCase();
        return osName.contains("mac") || osName.contains("darwin") ? "darwin" : "linux";
    }

    private String resolveArch() {
        String arch = System.getProperty("os.arch", "").toLowerCase();
        return (arch.contains("aarch64") || arch.contains("arm64")) ? "arm64" : "amd64";
    }
}
