package me.golemcore.bot.adapter.inbound.web.dto.settings;

import me.golemcore.bot.domain.model.RuntimeConfig;

public interface RuntimeSettingsWebDtos {

    public static class RuntimeConfigDto extends RuntimeConfig {
    }

    public static class ModelRouterConfigDto extends RuntimeConfig.ModelRouterConfig {
    }

    public static class LlmConfigDto extends RuntimeConfig.LlmConfig {
    }

    public static class LlmProviderConfigDto extends RuntimeConfig.LlmProviderConfig {
    }

    public static class ToolsConfigDto extends RuntimeConfig.ToolsConfig {
    }

    public static class ShellEnvironmentVariableDto extends RuntimeConfig.ShellEnvironmentVariable {
    }

    public static class VoiceConfigDto extends RuntimeConfig.VoiceConfig {
    }

    public static class TurnConfigDto extends RuntimeConfig.TurnConfig {
    }

    public static class ToolLoopConfigDto extends RuntimeConfig.ToolLoopConfig {
    }

    public static class SessionRetentionConfigDto extends RuntimeConfig.SessionRetentionConfig {
    }

    public static class MemoryConfigDto extends RuntimeConfig.MemoryConfig {
    }

    public static class SkillsConfigDto extends RuntimeConfig.SkillsConfig {
    }

    public static class UsageConfigDto extends RuntimeConfig.UsageConfig {
    }

    public static class TelemetryConfigDto extends RuntimeConfig.TelemetryConfig {
    }

    public static class McpConfigDto extends RuntimeConfig.McpConfig {
    }

    public static class McpCatalogEntryDto extends RuntimeConfig.McpCatalogEntry {
    }

    public static class PlanConfigDto extends RuntimeConfig.PlanConfig {
    }

    public static class HiveConfigDto extends RuntimeConfig.HiveConfig {
    }

    public static class AutoModeConfigDto extends RuntimeConfig.AutoModeConfig {
    }

    public static class TracingConfigDto extends RuntimeConfig.TracingConfig {
    }

    public static class RateLimitConfigDto extends RuntimeConfig.RateLimitConfig {
    }

    public static class SecurityConfigDto extends RuntimeConfig.SecurityConfig {
    }

    public static class CompactionConfigDto extends RuntimeConfig.CompactionConfig {
    }

    public static class ResilienceConfigDto extends RuntimeConfig.ResilienceConfig {
    }
}
