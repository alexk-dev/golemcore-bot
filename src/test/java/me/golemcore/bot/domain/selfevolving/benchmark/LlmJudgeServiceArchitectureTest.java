package me.golemcore.bot.domain.selfevolving.benchmark;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LlmJudgeServiceArchitectureTest {

    private static final Path SOURCE_PATH = Path.of(
            "src/main/java/me/golemcore/bot/domain/selfevolving/benchmark/LlmJudgeService.java");

    @Test
    void shouldNotConstructObjectMapperDirectlyInDomainService() {
        String source = readSource();

        assertFalse(source.contains("import com.fasterxml.jackson.databind.ObjectMapper;"),
                "LlmJudgeService should not import ObjectMapper directly");
        assertFalse(source.contains("new ObjectMapper()"),
                "LlmJudgeService should not construct ObjectMapper directly");
    }

    @Test
    void shouldDelegateSnapshotSerializationThroughPort() {
        String source = readSource();

        assertTrue(source.contains("TraceSnapshotCodecPort"),
                "LlmJudgeService should delegate snapshot serialization through TraceSnapshotCodecPort");
    }

    private String readSource() {
        try {
            return Files.readString(SOURCE_PATH);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read LlmJudgeService source", exception);
        }
    }
}
