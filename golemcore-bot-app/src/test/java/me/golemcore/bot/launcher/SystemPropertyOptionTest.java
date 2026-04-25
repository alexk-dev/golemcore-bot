package me.golemcore.bot.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SystemPropertyOptionTest {

    @Test
    void shouldAcceptJvmSystemPropertyForms() {
        assertTrue(SystemPropertyOption.isSystemPropertyArg("-Dfeature.enabled"));
        assertTrue(SystemPropertyOption.isSystemPropertyArg("-Dfeature.enabled="));
        assertTrue(SystemPropertyOption.isSystemPropertyArg("-Dfeature.enabled=true"));
    }

    @Test
    void shouldRejectMissingSystemPropertyName() {
        assertFalse(SystemPropertyOption.isSystemPropertyArg(null));
        assertFalse(SystemPropertyOption.isSystemPropertyArg("-D"));
        assertFalse(SystemPropertyOption.isSystemPropertyArg("-D=value"));
        assertFalse(SystemPropertyOption.isSystemPropertyArg("--Dfeature.enabled"));
    }

    @Test
    void shouldExtractSystemPropertyNameFromJvmForms() {
        assertEquals("feature.enabled", SystemPropertyOption.extractSystemPropertyName("-Dfeature.enabled"));
        assertEquals("feature.enabled", SystemPropertyOption.extractSystemPropertyName("-Dfeature.enabled="));
        assertEquals("feature.enabled", SystemPropertyOption.extractSystemPropertyName("-Dfeature.enabled=true"));
    }
}
