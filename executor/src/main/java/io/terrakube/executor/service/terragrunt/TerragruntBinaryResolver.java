package io.terrakube.executor.service.terragrunt;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.terrakube.executor.plugin.tfstate.TerraformState;
import io.terrakube.executor.service.mode.TerraformJob;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.semver4j.Semver;
import org.semver4j.range.RangeList;
import org.semver4j.range.RangeListFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Resolves the terragrunt version a job should use and ensures the matching
 * binary is available locally, restoring it from cloud storage (via
 * {@link TerraformState}) or downloading it from GitHub releases when it
 * isn't already cached. Mirrors how the terraform/tofu binary is resolved
 * and cached elsewhere in this module (see
 * TerraformExecutorServiceImpl#ensureBinaryCached/#saveBinaryToCache), since
 * the terraform-spring-boot-starter library that handles that only knows
 * about terraform/tofu, not terragrunt.
 *
 * <p>Version resolution follows the same convention as
 * {@code io.terrakube.terraform.TerraformDownloader#resolveTerraformVersion}:
 * an exact three-part version (e.g. "0.67.16") is used as-is with no network
 * call, while anything else (a partial version like "0.67", or a range like
 * "~>0.67.0") is resolved via semver4j's {@link RangeListFactory} against the
 * published GitHub releases for gruntwork-io/terragrunt, picking the highest
 * matching stable release - mirroring how Terraform/OpenTofu version
 * constraints already work in Terrakube.
 */
@Slf4j
@Service
public class TerragruntBinaryResolver {

    public static final String DEFAULT_TERRAGRUNT_VERSION = "0.67.16";

    private static final Pattern EXACT_VERSION = Pattern.compile("^\\d+\\.\\d+\\.\\d+$");
    private static final String TERRAGRUNT_DIRECTORY = "/.terraform-spring-boot/terragrunt/v";
    private static final String RELEASE_URL_TEMPLATE = "https://github.com/gruntwork-io/terragrunt/releases/download/v%s/terragrunt_%s_%s";
    private static final String RELEASES_API_URL = "https://api.github.com/repos/gruntwork-io/terragrunt/releases?per_page=100";
    private static final Duration RELEASE_CACHE_TTL = Duration.ofMinutes(10);

    private final TerraformState terraformState;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private volatile List<Semver> cachedReleases;
    private volatile Instant cachedAt = Instant.EPOCH;

    public TerragruntBinaryResolver(TerraformState terraformState, ObjectMapper objectMapper) {
        this.terraformState = terraformState;
        this.objectMapper = objectMapper;
    }

    /**
     * Resolves the workspace's requested terragrunt version (which may be an exact version,
     * a partial version, or a semver range) to a concrete, downloadable version.
     */
    public String resolveVersion(TerraformJob terraformJob) {
        String version = terraformJob.getTerragruntVersion();
        if (version == null || version.isBlank()) {
            return DEFAULT_TERRAGRUNT_VERSION;
        }

        String constraint = version.trim();
        if (EXACT_VERSION.matcher(constraint).matches()) {
            // Fast path: already a concrete version, no need to consult the release list.
            return constraint;
        }

        return selectBestMatch(fetchReleases(), constraint);
    }

    /**
     * Picks the highest release satisfying the given constraint. Package-private (not
     * private) so tests can exercise the matching logic against a synthetic release list
     * instead of depending on the live GitHub API.
     */
    String selectBestMatch(List<Semver> releases, String constraint) {
        RangeList rangeList = RangeListFactory.create(constraint);

        Semver best = null;
        for (Semver candidate : releases) {
            if (rangeList.isSatisfiedBy(candidate) && (best == null || candidate.compareTo(best) > 0)) {
                best = candidate;
            }
        }

        if (best == null) {
            throw new IllegalArgumentException(
                    "No terragrunt release found matching version constraint \"" + constraint + "\"");
        }

        return best.getVersion();
    }

    public File getBinaryFile(String version) {
        String directoryPath = FileUtils.getUserDirectoryPath()
                .concat(FilenameUtils.separatorsToSystem(TERRAGRUNT_DIRECTORY + version));
        return new File(directoryPath, "terragrunt");
    }

    /**
     * Ensures the terragrunt binary for the given (already-resolved, concrete) version exists
     * locally, restoring it from cloud storage or downloading it from GitHub if needed.
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

    /**
     * Fetches published (non-draft, non-prerelease) terragrunt releases from GitHub, cached
     * in-memory for {@link #RELEASE_CACHE_TTL} so a burst of jobs resolving the same version
     * constraint doesn't hammer the GitHub API. A stale cache is served (with a warning) if a
     * refresh attempt fails.
     */
    private synchronized List<Semver> fetchReleases() {
        if (cachedReleases != null && Instant.now().isBefore(cachedAt.plus(RELEASE_CACHE_TTL))) {
            return cachedReleases;
        }

        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(RELEASES_API_URL))
                    .header("Accept", "application/vnd.github+json")
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IOException("GitHub releases API returned HTTP " + response.statusCode());
            }

            List<Map<String, Object>> body = objectMapper.readValue(response.body(), new TypeReference<>() {
            });
            List<Semver> releases = new ArrayList<>();
            for (Map<String, Object> release : body) {
                if (Boolean.TRUE.equals(release.get("prerelease")) || Boolean.TRUE.equals(release.get("draft"))) {
                    continue;
                }
                String tag = String.valueOf(release.get("tag_name"));
                String candidate = tag.startsWith("v") ? tag.substring(1) : tag;
                Semver semver = Semver.parse(candidate);
                if (semver != null) {
                    releases.add(semver);
                }
            }

            cachedReleases = releases;
            cachedAt = Instant.now();
            return releases;
        } catch (Exception e) {
            if (cachedReleases != null) {
                log.warn("Failed to refresh terragrunt release list, using stale cache: {}", e.getMessage());
                return cachedReleases;
            }
            throw new IllegalStateException(
                    "Unable to resolve terragrunt version constraint - failed to fetch release list from GitHub", e);
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
