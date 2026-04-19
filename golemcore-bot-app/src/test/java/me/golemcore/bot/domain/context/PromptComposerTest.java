package me.golemcore.bot.domain.context;

import org.junit.jupiter.api.Test;

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
}
