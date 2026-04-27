package me.golemcore.bot.domain.skills;

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

    @SuppressWarnings("unchecked")
    @Test
    void shouldPreserveMcpAndVarsFieldsThroughRoundTrip() {
        String input = """
                ---
                name: github
                description: GitHub integration
                mcp:
                  command: npx -y @mcp/server-github
                  env:
                    GITHUB_TOKEN: ${GITHUB_TOKEN}
                  startup_timeout: 45
                  idle_timeout: 10
                vars:
                  api_key:
                    source: env
                    env_var: GITHUB_TOKEN
                    required: true
                next_skill: summary
                conditional_next_skills:
                  success: deploy
                  error: rollback
                ---
                GitHub integration skill.
                """;

        SkillDocument parsed = service.parseDocument(input);
        String rendered = service.renderDocument(parsed.metadata(), parsed.body());
        SkillDocument reparsed = service.parseDocument(rendered);

        assertEquals(parsed.metadata().get("name"), reparsed.metadata().get("name"));
        assertEquals(parsed.metadata().get("description"), reparsed.metadata().get("description"));
        assertEquals(parsed.metadata().get("next_skill"), reparsed.metadata().get("next_skill"));
        assertEquals(parsed.body(), reparsed.body());

        Map<String, Object> mcpOriginal = (Map<String, Object>) parsed.metadata().get("mcp");
        Map<String, Object> mcpReparsed = (Map<String, Object>) reparsed.metadata().get("mcp");
        assertEquals(mcpOriginal.get("command"), mcpReparsed.get("command"));
        assertEquals(mcpOriginal.get("startup_timeout"), mcpReparsed.get("startup_timeout"));
        assertEquals(mcpOriginal.get("idle_timeout"), mcpReparsed.get("idle_timeout"));

        Map<String, Object> envOriginal = (Map<String, Object>) mcpOriginal.get("env");
        Map<String, Object> envReparsed = (Map<String, Object>) mcpReparsed.get("env");
        assertEquals(envOriginal.get("GITHUB_TOKEN"), envReparsed.get("GITHUB_TOKEN"));

        Map<String, Object> conditionalOriginal = (Map<String, Object>) parsed.metadata()
                .get("conditional_next_skills");
        Map<String, Object> conditionalReparsed = (Map<String, Object>) reparsed.metadata()
                .get("conditional_next_skills");
        assertEquals(conditionalOriginal, conditionalReparsed);
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
