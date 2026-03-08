package me.golemcore.bot.plugin.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginVersionSupportTest {

    @Test
    void shouldCompareStableAndPreReleaseVersionsCorrectly() {
        assertTrue(PluginVersionSupport.compareVersions("1.0.0", "1.0.0-rc.1") > 0);
        assertTrue(PluginVersionSupport.compareVersions("1.0.0-rc.2", "1.0.0-rc.1") > 0);
        assertEquals(0, PluginVersionSupport.compareVersions("1.2.0", "1.2"));
    }

    @Test
    void shouldMatchCompoundVersionConstraints() {
        assertTrue(PluginVersionSupport.matchesVersionConstraint("1.2.3", ">=1.0.0 <2.0.0"));
        assertFalse(PluginVersionSupport.matchesVersionConstraint("2.0.0", ">=1.0.0 <2.0.0"));
    }

    @Test
    void shouldRejectUnsupportedConstraintTokens() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> PluginVersionSupport.matchesVersionConstraint("1.0.0", "^1.0.0"));
        assertTrue(error.getMessage().contains("Constraint token must start"));
    }

    @Test
    void shouldNormalizeSnapshotHostVersions() {
        assertEquals("1.4.0", PluginVersionSupport.normalizeHostVersion("1.4.0-SNAPSHOT"));
        assertEquals("0.12.0",
                PluginVersionSupport.normalizeHostVersion("0.12.0-feat_plugin_runtime_marketplace"));
        assertEquals("1.2.3",
                PluginVersionSupport.normalizeHostVersion("1.2.3-rc_1+build.5"));
        assertEquals("0.0.0", PluginVersionSupport.normalizeHostVersion(" "));
    }
}
