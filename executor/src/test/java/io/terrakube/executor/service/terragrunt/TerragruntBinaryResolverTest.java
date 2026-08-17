package io.terrakube.executor.service.terragrunt;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.terrakube.executor.plugin.tfstate.TerraformState;
import io.terrakube.executor.service.mode.TerraformJob;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.semver4j.Semver;

import java.io.File;
import java.nio.charset.Charset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TerragruntBinaryResolverTest {

    private final TerraformState terraformState = mock(TerraformState.class);
    private final TerragruntBinaryResolver resolver = new TerragruntBinaryResolver(terraformState, new ObjectMapper());

    @AfterEach
    void cleanup() {
        FileUtils.deleteQuietly(new File(FileUtils.getUserDirectoryPath(), ".terraform-spring-boot/terragrunt"));
    }

    @Test
    void resolveVersionFallsBackToDefaultWhenWorkspaceDoesNotPinAVersion() {
        TerraformJob job = new TerraformJob();

        assertEquals(TerragruntBinaryResolver.DEFAULT_TERRAGRUNT_VERSION, resolver.resolveVersion(job));

        job.setTerragruntVersion("   ");
        assertEquals(TerragruntBinaryResolver.DEFAULT_TERRAGRUNT_VERSION, resolver.resolveVersion(job));
    }

    @Test
    void resolveVersionUsesWorkspacePinnedVersionWhenSet() {
        TerraformJob job = new TerraformJob();
        job.setTerragruntVersion(" 0.55.1 ");

        assertEquals("0.55.1", resolver.resolveVersion(job));
    }

    @Test
    void selectBestMatchPicksHighestReleaseWithinAPartialVersion() {
        List<Semver> releases = List.of(
                Semver.parse("0.67.0"),
                Semver.parse("0.67.16"),
                Semver.parse("0.67.5"),
                Semver.parse("0.68.0"),
                Semver.parse("0.66.9"));

        assertEquals("0.67.16", resolver.selectBestMatch(releases, "0.67"));
    }

    @Test
    void selectBestMatchHonorsRangeConstraints() {
        List<Semver> releases = List.of(
                Semver.parse("0.55.0"),
                Semver.parse("0.60.0"),
                Semver.parse("0.65.9"),
                Semver.parse("0.70.0"));

        assertEquals("0.65.9", resolver.selectBestMatch(releases, ">=0.60.0 <0.70.0"));
    }

    @Test
    void selectBestMatchThrowsClearlyWhenNothingSatisfiesTheConstraint() {
        List<Semver> releases = List.of(Semver.parse("0.55.0"), Semver.parse("0.60.0"));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> resolver.selectBestMatch(releases, "9.9"));
        assertTrue(exception.getMessage().contains("9.9"));
    }

    @Test
    void getBinaryFileBuildsPathUnderTerraformSpringBootHome() {
        File binaryFile = resolver.getBinaryFile("0.67.16");

        assertTrue(binaryFile.getAbsolutePath().contains(".terraform-spring-boot"));
        assertTrue(binaryFile.getAbsolutePath().contains("terragrunt"));
        assertTrue(binaryFile.getAbsolutePath().contains("0.67.16"));
        assertEquals("terragrunt", binaryFile.getName());
    }

    @Test
    void ensureBinaryReturnsExistingLocalBinaryWithoutTouchingCloudCache() throws Exception {
        String version = "0.67.16";
        File binaryFile = resolver.getBinaryFile(version);
        FileUtils.forceMkdirParent(binaryFile);
        FileUtils.writeStringToFile(binaryFile, "already-here", Charset.defaultCharset());

        File result = resolver.ensureBinary(version);

        assertEquals(binaryFile, result);
        verify(terraformState, never()).downloadTerragruntBinary(anyString(), any());
    }

    @Test
    void ensureBinaryRestoresFromCloudCacheWhenNotPresentLocally() {
        String version = "0.55.1";
        when(terraformState.downloadTerragruntBinary(org.mockito.ArgumentMatchers.eq(version), any()))
                .thenReturn(true);

        File result = resolver.ensureBinary(version);

        assertEquals(resolver.getBinaryFile(version), result);
        verify(terraformState).downloadTerragruntBinary(org.mockito.ArgumentMatchers.eq(version), any());
    }
}
