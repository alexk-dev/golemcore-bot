package me.golemcore.bot.client.dto.settings;

import me.golemcore.bot.domain.model.RuntimeConfig;

import java.util.List;

public interface SettingsWebDtos {

    public record ModelDto(String id, String displayName, boolean hasReasoning, List<String> reasoningLevels,
            boolean supportsVision) {
    }

    public record LlmProviderImportRequest(RuntimeConfig.LlmProviderConfig config, List<String> selectedModelIds) {
    }

    public record LlmProviderImportResponse(boolean providerSaved, String providerName, String resolvedEndpoint,
            List<String> addedModels, List<String> skippedModels, List<String> errors) {
    }

    public record LlmProviderTestRequest(String mode, String providerName, RuntimeConfig.LlmProviderConfig config) {
    }

    public record LlmProviderTestResponse(String mode, String providerName, String resolvedEndpoint,
            List<String> models, boolean success, String error) {
    }

    public record AdvancedConfigRequest(RuntimeSettingsWebDtos.RateLimitConfigDto rateLimit,
            RuntimeSettingsWebDtos.SecurityConfigDto security,
            RuntimeSettingsWebDtos.CompactionConfigDto compaction,
            RuntimeSettingsWebDtos.ResilienceConfigDto resilience) {
    }
}
