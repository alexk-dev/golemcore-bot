package me.golemcore.bot.domain.loop;

import me.golemcore.bot.domain.runtimeconfig.UserPreferencesService;

final class SafeErrorFeedbackRenderer {

    private final UserPreferencesService preferencesService;

    SafeErrorFeedbackRenderer(UserPreferencesService preferencesService) {
        this.preferencesService = preferencesService;
    }

    String renderGenericFallback() {
        return preferencesService.getMessage("system.error.generic.feedback");
    }

    String renderExplainedFallback(String safeExplanation) {
        if (safeExplanation == null || safeExplanation.isBlank()) {
            return renderGenericFallback();
        }
        return preferencesService.getMessage("system.error.feedback", safeExplanation);
    }
}
