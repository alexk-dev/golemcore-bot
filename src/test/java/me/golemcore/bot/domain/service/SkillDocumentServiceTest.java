package me.golemcore.bot.domain.service;

import me.golemcore.bot.domain.model.SkillDocument;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillDocumentServiceTest {

    private final SkillDocumentService service = new SkillDocumentService();

    @Test
    void shouldParsePlainDocumentWithoutFrontmatter() {
        SkillDocument document = service.parseNormalizedDocument("Body only");

        assertFalse(document.hasFrontmatter());
        assertEquals(Map.of(), document.metadata());
        assertEquals("Body only", document.body());
    }

    @Test
    void shouldParseTopLevelFrontmatterAndBody() {
        SkillDocument document = service.parseNormalizedDocument("""
                ---
                name: reviewer
                description: Review code
                ---
                Inspect the diff.
                """);

        assertTrue(document.hasFrontmatter());
        assertEquals("reviewer", document.metadata().get("name"));
        assertEquals("Review code", document.metadata().get("description"));
        assertEquals("Inspect the diff.", document.body());
    }

    @Test
    void shouldMergeNestedFrontmatterIntoMetadataAndStripBodyHeaders() {
        SkillDocument document = service.parseNormalizedDocument("""
                ---
                name: reviewer
                description: Review code
                ---
                ---
                model_tier: coding
                next_skill: summary
                ---
                Inspect the diff.
                """);

        assertTrue(document.hasFrontmatter());
        assertEquals("reviewer", document.metadata().get("name"));
        assertEquals("Review code", document.metadata().get("description"));
        assertEquals("coding", document.metadata().get("model_tier"));
        assertEquals("summary", document.metadata().get("next_skill"));
        assertEquals("Inspect the diff.", document.body());
    }

    @Test
    void shouldPreserveTopLevelMetadataWhenNestedHeaderRepeatsTheSameFields() {
        SkillDocument document = service.parseNormalizedDocument("""
                ---
                name: canonical-name
                description: Canonical description
                ---
                ---
                name: ignored-name
                description: Ignored description
                model_tier: smart
                ---
                Body
                """);

        assertEquals("canonical-name", document.metadata().get("name"));
        assertEquals("Canonical description", document.metadata().get("description"));
        assertEquals("smart", document.metadata().get("model_tier"));
        assertEquals("Body", document.body());
    }

    @Test
    void shouldStripParsedHeaderEvenWhenYamlIsInvalid() {
        SkillDocument document = service.parseNormalizedDocument("""
                ---
                name: reviewer
                ---
                ---
                vars: [broken
                ---
                Body
                """);

        assertTrue(document.hasFrontmatter());
        assertEquals("reviewer", document.metadata().get("name"));
        assertEquals("Body", document.body());
    }

    @Test
    void shouldRenderOrderedFrontmatterBeforeBody() {
        String rendered = service.renderDocument(Map.of(
                "model_tier", "coding",
                "description", "Review code",
                "name", "reviewer",
                "custom_field", "kept"), "Inspect the diff.");

        assertTrue(rendered.startsWith(
                "---\nname: reviewer\ndescription: Review code\nmodel_tier: coding\ncustom_field: kept\n---\nInspect the diff."));
    }
}
