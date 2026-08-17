package io.terrakube.executor.service.terragrunt;

import io.terrakube.executor.plugin.tfstate.TerraformState;
import io.terrakube.executor.service.mode.TerraformJob;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.Charset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TerragruntBinaryResolverTest {

    private final TerraformState terraformState = mock(TerraformState.class);
    private final TerragruntBinaryResolver resolver = new TerragruntBinaryResolver(terraformState);

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
