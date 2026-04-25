package me.golemcore.bot.domain.system.toolloop.view;

import me.golemcore.bot.domain.model.Message;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolArtifactReferenceFormatterTest {

    @Test
    void shouldIgnoreMissingArtifactMetadata() {
        StringBuilder content = new StringBuilder("summary");

        ToolArtifactReferenceFormatter.appendArtifactRefs(null, toolResult(null));
        ToolArtifactReferenceFormatter.appendArtifactRefs(content, null);
        ToolArtifactReferenceFormatter.appendArtifactRefs(content, toolResult(null));
        ToolArtifactReferenceFormatter.appendArtifactRefs(content, toolResult(Map.of("toolAttachments", List.of())));

        assertEquals("summary", content.toString());
    }

    @Test
    void shouldAppendDurableArtifactReferencesAndIgnoreInvalidEntries() {
        Message result = toolResult(Map.of("toolAttachments", List.of(
                "not-an-attachment",
                Map.of("name", "   "),
                Map.of("name", "page.html", "internalFilePath", "artifacts/page.html"),
                Map.of("url", "/api/tool-artifacts/result.json"),
                Map.of("name", "both.txt", "internalFilePath", "artifacts/both.txt",
                        "url", "/api/tool-artifacts/both.txt"))));
        StringBuilder content = new StringBuilder("summary");

        ToolArtifactReferenceFormatter.appendArtifactRefs(content, result);

        String rendered = content.toString();
        assertTrue(rendered.contains("Artifact: page.html path=artifacts/page.html"));
        assertTrue(rendered.contains("Artifact: tool-output url=/api/tool-artifacts/result.json"));
        assertTrue(rendered.contains("Artifact: both.txt path=artifacts/both.txt url=/api/tool-artifacts/both.txt"));
        assertEquals(3, rendered.split("Artifact:", -1).length - 1);
    }

    private Message toolResult(Map<String, Object> metadata) {
        return Message.builder()
                .role("tool")
                .content("raw")
                .metadata(metadata)
                .build();
    }
}
