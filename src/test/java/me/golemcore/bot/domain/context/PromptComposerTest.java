package me.golemcore.bot.domain.context;

import org.junit.jupiter.api.Test;

import me.golemcore.bot.domain.context.layer.TokenEstimator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptComposerTest {

    private final PromptComposer composer = new PromptComposer();

    @Test
    void shouldJoinLayerContentWithDoubleNewlines() {
        ContextBlueprint blueprint = ContextBlueprint.create();
        blueprint.add(ContextLayerResult.builder()
                .layerName("identity").content("# Identity\nYou are Bot.").build());
        blueprint.add(ContextLayerResult.builder()
                .layerName("memory").content("# Memory\nUser prefers Java.").build());

        String result = composer.compose(blueprint);

        assertTrue(result.contains("# Identity\nYou are Bot."));
        assertTrue(result.contains("# Memory\nUser prefers Java."));
        assertTrue(result.indexOf("Bot.") < result.indexOf("# Memory"));
    }

    @Test
    void shouldSkipEmptyLayerResults() {
        ContextBlueprint blueprint = ContextBlueprint.create();
        blueprint.add(ContextLayerResult.builder()
                .layerName("identity").content("# Identity").build());
        blueprint.add(ContextLayerResult.empty("memory"));
        blueprint.add(ContextLayerResult.builder()
                .layerName("tools").content("# Tools").build());

        String result = composer.compose(blueprint);

        assertTrue(result.contains("# Identity"));
        assertTrue(result.contains("# Tools"));
        assertEquals(-1, result.indexOf("memory"));
    }

    @Test
    void shouldReturnFallbackWhenBlueprintIsNull() {
        String result = composer.compose(null);
        assertEquals("You are a helpful AI assistant.", result);
    }

    @Test
    void shouldReturnFallbackWhenNoLayersHaveContent() {
        ContextBlueprint blueprint = ContextBlueprint.create();
        blueprint.add(ContextLayerResult.empty("a"));
        blueprint.add(ContextLayerResult.empty("b"));

        String result = composer.compose(blueprint);
        assertEquals("You are a helpful AI assistant.", result);
    }

    @Test
    void shouldTrimWhitespace() {
        ContextBlueprint blueprint = ContextBlueprint.create();
        blueprint.add(ContextLayerResult.builder()
                .layerName("test").content("  content  ").build());

        String result = composer.compose(blueprint);
        assertEquals("content", result);
    }

    @Test
    void shouldKeepRequiredAndHigherPriorityOptionalLayersWithinBudget() {
        ContextBlueprint blueprint = ContextBlueprint.create();
        blueprint.add(ContextLayerResult.builder()
                .layerName("identity")
                .content("# Identity")
                .estimatedTokens(200)
                .required(true)
                .priority(100)
                .build());
        blueprint.add(ContextLayerResult.builder()
                .layerName("memory")
                .content("# Memory")
                .estimatedTokens(500)
                .priority(40)
                .build());
        blueprint.add(ContextLayerResult.builder()
                .layerName("tool")
                .content("# Tools")
                .estimatedTokens(300)
                .priority(70)
                .build());

        String result = composer.compose(blueprint, 500);

        assertTrue(result.contains("# Identity"));
        assertTrue(result.contains("# Tools"));
        assertEquals(-1, result.indexOf("# Memory"));
        assertTrue(result.indexOf("# Identity") < result.indexOf("# Tools"),
                "selected layers must keep blueprint order");
    }

    @Test
    void shouldDropOptionalLayerThatExceedsItsOwnBudget() {
        ContextBlueprint blueprint = ContextBlueprint.create();
        blueprint.add(ContextLayerResult.builder()
                .layerName("identity")
                .content("# Identity")
                .estimatedTokens(100)
                .required(true)
                .priority(100)
                .build());
        blueprint.add(ContextLayerResult.builder()
                .layerName("rag")
                .content("# Relevant Memory")
                .estimatedTokens(600)
                .priority(90)
                .tokenBudget(300)
                .build());

        String result = composer.compose(blueprint, 1_000);

        assertTrue(result.contains("# Identity"));
        assertEquals(-1, result.indexOf("# Relevant Memory"));
    }

    @Test
    void shouldKeepRequiredLayerEvenWhenItExceedsGlobalBudget() {
        ContextBlueprint blueprint = ContextBlueprint.create();
        blueprint.add(ContextLayerResult.builder()
                .layerName("identity")
                .content("# Identity")
                .estimatedTokens(2_000)
                .required(true)
                .priority(100)
                .build());
        blueprint.add(ContextLayerResult.builder()
                .layerName("memory")
                .content("# Memory")
                .estimatedTokens(10)
                .priority(40)
                .build());

        String result = composer.compose(blueprint, 100);

        assertTrue(result.contains("# Identity"));
        assertEquals(-1, result.indexOf("# Memory"));
    }

    @Test
    void shouldTruncateRequiredLayerToEnforceHardBudget() {
        ContextBlueprint blueprint = ContextBlueprint.create();
        blueprint.add(ContextLayerResult.builder()
                .layerName("identity")
                .content("# Identity\n" + "required ".repeat(2_000))
                .estimatedTokens(2_000)
                .required(true)
                .priority(100)
                .build());

        String result = composer.compose(blueprint, 120);

        assertTrue(TokenEstimator.estimate(result) <= 120);
        assertTrue(result.contains("Layer truncated by system prompt budget"));
    }

    @Test
    void shouldKeepFallbackWithinHardBudget() {
        String result = composer.compose(null, 1);

        assertTrue(TokenEstimator.estimate(result) <= 1);
    }
}
