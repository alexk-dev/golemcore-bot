package me.golemcore.bot.domain.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class DelayedSessionActionServiceArchitectureTest {

    private static final Path SOURCE_PATH = Path.of(
            "src/main/java/me/golemcore/bot/domain/service/DelayedSessionActionService.java");

    @Test
    void shouldNotConstructObjectMapperDirectlyInDomainService() {
        String source = readSource();

        assertFalse(source.contains("import com.fasterxml.jackson.databind.ObjectMapper;"),
                "DelayedSessionActionService should not import ObjectMapper directly");
        assertFalse(source.contains("new ObjectMapper()"),
                "DelayedSessionActionService should not construct ObjectMapper directly");
    }

    @Test
    void shouldDelegateRegistryPersistenceThroughPort() {
        String source = readSource();

        assertTrue(source.contains("DelayedActionRegistryPort"),
                "DelayedSessionActionService should delegate persistence to DelayedActionRegistryPort");
    }

    private String readSource() {
        try {
            return Files.readString(SOURCE_PATH);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read DelayedSessionActionService source", exception);
        }
    }
}
