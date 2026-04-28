package me.golemcore.bot.domain.hive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class HiveJoinCodeParserTest {

    @Test
    void shouldParseJoinCodeAndNormalizeServerUrl() {
        HiveJoinCodeParser.ParsedJoinCode parsed = HiveJoinCodeParser
                .parse("token-id.secret:https://hive.example.com/");

        assertEquals("token-id.secret", parsed.enrollmentToken());
        assertEquals("https://hive.example.com", parsed.serverUrl());
    }

    @Test
    void shouldRejectJoinCodeWithoutHttpUrl() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> HiveJoinCodeParser.parse("token-id.secret:not-a-url"));

        assertEquals("Hive joinCode server URL must be a valid http(s) URL", error.getMessage());
    }

    @Test
    void shouldReturnNullWhenServerUrlExtractionFails() {
        assertNull(HiveJoinCodeParser.tryExtractServerUrl("broken"));
    }
}
