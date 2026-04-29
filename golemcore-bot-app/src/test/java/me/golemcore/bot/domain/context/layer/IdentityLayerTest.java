package me.golemcore.bot.domain.context.layer;

import me.golemcore.bot.domain.context.ContextLayerResult;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.PromptSection;
import me.golemcore.bot.domain.model.UserPreferences;
import me.golemcore.bot.domain.prompt.PromptSectionService;
import me.golemcore.bot.domain.runtimeconfig.UserPreferencesService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IdentityLayerTest {

    private PromptSectionService promptSectionService;
    private UserPreferencesService userPreferencesService;
    private IdentityLayer layer;

    @BeforeEach
    void setUp() {
        promptSectionService = mock(PromptSectionService.class);
        userPreferencesService = mock(UserPreferencesService.class);
        when(userPreferencesService.getPreferences()).thenReturn(UserPreferences.builder().build());
        layer = new IdentityLayer(promptSectionService, userPreferencesService);
    }

    @Test
    void shouldAlwaysApply() {
        assertTrue(layer.appliesTo(AgentContext.builder().build()));
    }

    @Test
    void shouldRenderEnabledSections() {
        PromptSection identity = PromptSection.builder()
                .name("identity").content("You are Bot.").order(10).enabled(true).build();
        PromptSection rules = PromptSection.builder()
                .name("rules").content("## Rules\n1. Be helpful.").order(20).enabled(true).build();

        when(promptSectionService.isEnabled()).thenReturn(true);
        when(promptSectionService.getEnabledSections()).thenReturn(List.of(identity, rules));
        when(promptSectionService.buildTemplateVariables(any())).thenReturn(Map.of());
        when(promptSectionService.renderSection(eq(identity), any())).thenReturn("You are Bot.");
        when(promptSectionService.renderSection(eq(rules), any())).thenReturn("## Rules\n1. Be helpful.");

        ContextLayerResult result = layer.assemble(AgentContext.builder().build());

        assertTrue(result.hasContent());
        assertTrue(result.getContent().contains("You are Bot."));
        assertTrue(result.getContent().contains("## Rules"));
    }

    @Test
    void shouldReturnFallbackWhenSectionsDisabled() {
        when(promptSectionService.isEnabled()).thenReturn(false);

        ContextLayerResult result = layer.assemble(AgentContext.builder().build());

        assertTrue(result.hasContent());
        assertTrue(result.getContent().contains("helpful AI assistant"));
    }

    @Test
    void shouldReturnFallbackWhenNoSectionsLoaded() {
        when(promptSectionService.isEnabled()).thenReturn(true);
        when(promptSectionService.getEnabledSections()).thenReturn(List.of());
        when(promptSectionService.buildTemplateVariables(any())).thenReturn(Map.of());

        ContextLayerResult result = layer.assemble(AgentContext.builder().build());

        assertTrue(result.hasContent());
        assertTrue(result.getContent().contains("helpful AI assistant"));
    }

    @Test
    void shouldHaveCorrectNameAndOrder() {
        assertEquals("identity", layer.getName());
        assertEquals(10, layer.getOrder());
    }
}
