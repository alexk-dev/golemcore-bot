package me.golemcore.bot.domain.selfevolving.promotion;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PromotionWorkflowStoreArchitectureTest {

    private static final Path SOURCE_PATH = Path.of(
            "src/main/java/me/golemcore/bot/domain/selfevolving/promotion/PromotionWorkflowStore.java");

    @Test
    void shouldNotConstructObjectMapperDirectlyInDomainService() {
        String source = readSource();

        assertFalse(source.contains("import com.fasterxml.jackson.databind.ObjectMapper;"),
                "PromotionWorkflowStore should not import ObjectMapper directly");
        assertFalse(source.contains("new ObjectMapper()"),
                "PromotionWorkflowStore should not construct ObjectMapper directly");
    }

    @Test
    void shouldDelegatePromotionWorkflowPersistenceThroughPort() {
        String source = readSource();

        assertTrue(source.contains("PromotionWorkflowStatePort"),
                "PromotionWorkflowStore should delegate persistence through PromotionWorkflowStatePort");
    }

    private String readSource() {
        try {
            return Files.readString(SOURCE_PATH);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read PromotionWorkflowStore source", exception);
        }
    }
}
