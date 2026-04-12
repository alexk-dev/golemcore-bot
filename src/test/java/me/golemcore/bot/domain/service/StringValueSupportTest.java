package me.golemcore.bot.domain.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StringValueSupportTest {

    @Test
    void shouldHandleBlankAndNullSafeStringHelpers() {
        assertTrue(StringValueSupport.isBlank(null));
        assertTrue(StringValueSupport.isBlank("   "));
        assertFalse(StringValueSupport.isBlank("value"));

        assertEquals("", StringValueSupport.nullSafe(null));
        assertEquals("value", StringValueSupport.nullSafe("value"));
    }
}
