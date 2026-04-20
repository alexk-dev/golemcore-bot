package me.golemcore.bot.domain.selfevolving.tactic;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class TacticRecordServiceArchitectureTest {

    private static final Path SOURCE_PATH = Path.of(
            "src/main/java/me/golemcore/bot/domain/selfevolving/tactic/TacticRecordService.java");

    @Test
    void shouldNotConstructObjectMapperDirectlyInDomainService() {
        String source = readSource();

        assertFalse(source.contains("import com.fasterxml.jackson.databind.ObjectMapper;"),
                "TacticRecordService should not import ObjectMapper directly");
        assertFalse(source.contains("new ObjectMapper()"),
                "TacticRecordService should not construct ObjectMapper directly");
    }

    @Test
    void shouldDelegateTacticPersistenceThroughPort() {
        String source = readSource();

        assertTrue(source.contains("TacticRecordStorePort"),
                "TacticRecordService should delegate persistence through TacticRecordStorePort");
    }

    private String readSource() {
        try {
            return Files.readString(SOURCE_PATH);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read TacticRecordService source", exception);
        }
    }
}
