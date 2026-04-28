package me.golemcore.bot.domain.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import me.golemcore.bot.domain.runtimeconfig.UserPreferencesService;
import org.junit.jupiter.api.Test;

class SafeErrorFeedbackRendererTest {

    @Test
    void shouldRenderGenericFallbackWhenExplanationIsBlank() {
        UserPreferencesService preferences = mock(UserPreferencesService.class);
        when(preferences.getMessage("system.error.generic.feedback")).thenReturn("Generic fallback");
        SafeErrorFeedbackRenderer renderer = new SafeErrorFeedbackRenderer(preferences);

        assertEquals("Generic fallback", renderer.renderExplainedFallback(" "));
    }

    @Test
    void shouldRenderGenericFallbackWhenExplanationIsMissing() {
        UserPreferencesService preferences = mock(UserPreferencesService.class);
        when(preferences.getMessage("system.error.generic.feedback")).thenReturn("Generic fallback");
        SafeErrorFeedbackRenderer renderer = new SafeErrorFeedbackRenderer(preferences);

        assertEquals("Generic fallback", renderer.renderExplainedFallback(null));
        assertEquals("Generic fallback", renderer.renderGenericFallback());
    }

    @Test
    void shouldRenderExplainedFallbackWhenExplanationIsPresent() {
        UserPreferencesService preferences = mock(UserPreferencesService.class);
        when(preferences.getMessage("system.error.feedback", "safe")).thenReturn("Explained fallback");
        SafeErrorFeedbackRenderer renderer = new SafeErrorFeedbackRenderer(preferences);

        assertEquals("Explained fallback", renderer.renderExplainedFallback("safe"));
    }
}
