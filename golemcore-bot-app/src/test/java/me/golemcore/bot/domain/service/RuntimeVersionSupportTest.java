package me.golemcore.bot.domain.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeVersionSupportTest {

    private final RuntimeVersionSupport runtimeVersionSupport = new RuntimeVersionSupport();

    @Test
    void shouldNormalizePrefixedJarAndExecVersions() {
        assertEquals("1.2.3", runtimeVersionSupport.normalizeVersion("v1.2.3.jar"));
        assertEquals("2.3.4", runtimeVersionSupport.normalizeVersion("V2.3.4-exec"));
        assertEquals("1.2.3-rc.1", runtimeVersionSupport.normalizeVersion("v1.2.3-rc.1.jar"));
    }

    @Test
    void shouldExtractSemanticVersionFromAssetNames() {
        assertEquals("1.2.3", runtimeVersionSupport.extractVersionFromAssetName("bot-1.2.3.jar"));
        assertEquals("1.2.3-rc.1", runtimeVersionSupport.extractVersionFromAssetName("release-v1.2.3-rc.1.jar"));
        assertEquals("2.3.4-beta-2", runtimeVersionSupport.extractVersionFromAssetName("release-V2.3.4-beta-2.jar"));
        assertEquals("1.2.3", runtimeVersionSupport.extractVersionFromAssetName("bot-1.2.3-exec.jar"));
    }

    @Test
    void shouldReturnNullWhenAssetNameDoesNotContainSemanticVersion() {
        assertNull(runtimeVersionSupport.extractVersionFromAssetName("bot-latest.jar"));
        assertNull(runtimeVersionSupport.extractVersionFromAssetName("bot-1.2.jar"));
        assertNull(runtimeVersionSupport.extractVersionFromAssetName(""));
        assertNull(runtimeVersionSupport.extractVersionFromAssetName(null));
    }

    @Test
    void shouldRecognizeSemanticVersionsAndIgnoreInvalidOnes() {
        assertTrue(runtimeVersionSupport.isSemanticVersion("1.2.3"));
        assertTrue(runtimeVersionSupport.isSemanticVersion("1.2.3-rc.1"));
        assertTrue(runtimeVersionSupport.isSemanticVersion("v1.2.3+build.1"));
        assertFalse(runtimeVersionSupport.isSemanticVersion("dev"));
        assertFalse(runtimeVersionSupport.isSemanticVersion("1.2"));
        assertFalse(runtimeVersionSupport.isSemanticVersion("release-10"));
    }

    @Test
    void shouldCompareSemanticVersionsAcrossMainSegmentsAndPrerelease() {
        assertTrue(runtimeVersionSupport.compareVersions("1.2.4", "1.2.3") > 0);
        assertTrue(runtimeVersionSupport.compareVersions("1.3.0", "1.2.9") > 0);
        assertTrue(runtimeVersionSupport.compareVersions("2.0.0", "1.9.9") > 0);
        assertTrue(runtimeVersionSupport.compareVersions("1.2.3", "1.2.3-rc.1") > 0);
        assertTrue(runtimeVersionSupport.compareVersions("1.2.3-rc.2", "1.2.3-rc.1") > 0);
        assertTrue(runtimeVersionSupport.compareVersions("1.2.3-alpha", "1.2.3-1") > 0);
        assertTrue(runtimeVersionSupport.compareVersions("1.2.3-rc.1.1", "1.2.3-rc.1") > 0);
        assertEquals(0, runtimeVersionSupport.compareVersions("v1.2.3+build.1", "1.2.3"));
    }

    @Test
    void shouldFallbackToNormalizedLexicographicComparisonForNonSemanticVersions() {
        assertTrue(runtimeVersionSupport.compareVersions("release-2", "release-10") > 0);
        assertTrue(runtimeVersionSupport.compareVersions("branch-z", "branch-a") > 0);
    }

    @Test
    void shouldReportWhenRemoteVersionIsNewer() {
        assertTrue(runtimeVersionSupport.isRemoteVersionNewer("1.2.4", "1.2.3"));
        assertFalse(runtimeVersionSupport.isRemoteVersionNewer("1.2.3", "1.2.3"));
        assertFalse(runtimeVersionSupport.isRemoteVersionNewer("1.2.3-rc.1", "1.2.3"));
    }
}
