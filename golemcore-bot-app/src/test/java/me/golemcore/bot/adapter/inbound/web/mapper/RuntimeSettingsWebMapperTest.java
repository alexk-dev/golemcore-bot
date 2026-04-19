package me.golemcore.bot.adapter.inbound.web.mapper;

import me.golemcore.bot.adapter.inbound.web.dto.settings.RuntimeSettingsWebDtos.AutoModeConfigDto;
import me.golemcore.bot.adapter.inbound.web.dto.settings.RuntimeSettingsWebDtos.CompactionConfigDto;
import me.golemcore.bot.adapter.inbound.web.dto.settings.RuntimeSettingsWebDtos.HiveConfigDto;
import me.golemcore.bot.adapter.inbound.web.dto.settings.RuntimeSettingsWebDtos.LlmConfigDto;
import me.golemcore.bot.adapter.inbound.web.dto.settings.RuntimeSettingsWebDtos.LlmProviderConfigDto;
import me.golemcore.bot.adapter.inbound.web.dto.settings.RuntimeSettingsWebDtos.McpCatalogEntryDto;
import me.golemcore.bot.adapter.inbound.web.dto.settings.RuntimeSettingsWebDtos.McpConfigDto;
import me.golemcore.bot.adapter.inbound.web.dto.settings.RuntimeSettingsWebDtos.MemoryConfigDto;
import me.golemcore.bot.adapter.inbound.web.dto.settings.RuntimeSettingsWebDtos.ModelRouterConfigDto;
import me.golemcore.bot.adapter.inbound.web.dto.settings.RuntimeSettingsWebDtos.PlanConfigDto;
import me.golemcore.bot.adapter.inbound.web.dto.settings.RuntimeSettingsWebDtos.RateLimitConfigDto;
import me.golemcore.bot.adapter.inbound.web.dto.settings.RuntimeSettingsWebDtos.RuntimeConfigDto;
import me.golemcore.bot.adapter.inbound.web.dto.settings.RuntimeSettingsWebDtos.SessionRetentionConfigDto;
import me.golemcore.bot.adapter.inbound.web.dto.settings.RuntimeSettingsWebDtos.SecurityConfigDto;
import me.golemcore.bot.adapter.inbound.web.dto.settings.RuntimeSettingsWebDtos.ShellEnvironmentVariableDto;
import me.golemcore.bot.adapter.inbound.web.dto.settings.RuntimeSettingsWebDtos.SkillsConfigDto;
import me.golemcore.bot.adapter.inbound.web.dto.settings.RuntimeSettingsWebDtos.TelemetryConfigDto;
import me.golemcore.bot.adapter.inbound.web.dto.settings.RuntimeSettingsWebDtos.ToolsConfigDto;
import me.golemcore.bot.adapter.inbound.web.dto.settings.RuntimeSettingsWebDtos.ToolLoopConfigDto;
import me.golemcore.bot.adapter.inbound.web.dto.settings.RuntimeSettingsWebDtos.TracingConfigDto;
import me.golemcore.bot.adapter.inbound.web.dto.settings.RuntimeSettingsWebDtos.TurnConfigDto;
import me.golemcore.bot.adapter.inbound.web.dto.settings.RuntimeSettingsWebDtos.UsageConfigDto;
import me.golemcore.bot.adapter.inbound.web.dto.settings.RuntimeSettingsWebDtos.VoiceConfigDto;
import me.golemcore.bot.domain.model.RuntimeConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class RuntimeSettingsWebMapperTest {

    private final RuntimeSettingsWebMapper mapper = new RuntimeSettingsWebMapper();

    @Test
    void shouldMapRuntimeSettingsSectionsBothWays() {
        assertNotNull(mapper.toRuntimeConfigDto(RuntimeConfig.builder().build()));
        assertNotNull(mapper.toRuntimeConfig(new RuntimeConfigDto()));
        assertNotNull(mapper.toModelRouterConfigDto(new RuntimeConfig.ModelRouterConfig()));
        assertNotNull(mapper.toModelRouterConfig(new ModelRouterConfigDto()));
        assertNotNull(mapper.toLlmConfigDto(new RuntimeConfig.LlmConfig()));
        assertNotNull(mapper.toLlmConfig(new LlmConfigDto()));
        assertNotNull(mapper.toLlmProviderConfigDto(new RuntimeConfig.LlmProviderConfig()));
        assertNotNull(mapper.toLlmProviderConfig(new LlmProviderConfigDto()));
        assertNotNull(mapper.toToolsConfigDto(new RuntimeConfig.ToolsConfig()));
        assertNotNull(mapper.toToolsConfig(new ToolsConfigDto()));
        assertNotNull(mapper.toShellEnvironmentVariableDto(new RuntimeConfig.ShellEnvironmentVariable()));
        assertNotNull(mapper.toShellEnvironmentVariable(new ShellEnvironmentVariableDto()));
        assertNotNull(mapper.toVoiceConfigDto(new RuntimeConfig.VoiceConfig()));
        assertNotNull(mapper.toVoiceConfig(new VoiceConfigDto()));
        assertNotNull(mapper.toTurnConfigDto(new RuntimeConfig.TurnConfig()));
        assertNotNull(mapper.toTurnConfig(new TurnConfigDto()));
        assertNotNull(mapper.toToolLoopConfigDto(new RuntimeConfig.ToolLoopConfig()));
        assertNotNull(mapper.toToolLoopConfig(new ToolLoopConfigDto()));
        assertNotNull(mapper.toSessionRetentionConfigDto(new RuntimeConfig.SessionRetentionConfig()));
        assertNotNull(mapper.toSessionRetentionConfig(new SessionRetentionConfigDto()));
        assertNotNull(mapper.toMemoryConfigDto(new RuntimeConfig.MemoryConfig()));
        assertNotNull(mapper.toMemoryConfig(new MemoryConfigDto()));
        assertNotNull(mapper.toSkillsConfigDto(new RuntimeConfig.SkillsConfig()));
        assertNotNull(mapper.toSkillsConfig(new SkillsConfigDto()));
        assertNotNull(mapper.toUsageConfigDto(new RuntimeConfig.UsageConfig()));
        assertNotNull(mapper.toUsageConfig(new UsageConfigDto()));
        assertNotNull(mapper.toTelemetryConfigDto(new RuntimeConfig.TelemetryConfig()));
        assertNotNull(mapper.toTelemetryConfig(new TelemetryConfigDto()));
        assertNotNull(mapper.toMcpConfigDto(new RuntimeConfig.McpConfig()));
        assertNotNull(mapper.toMcpConfig(new McpConfigDto()));
        assertNotNull(mapper.toMcpCatalogEntryDto(new RuntimeConfig.McpCatalogEntry()));
        assertNotNull(mapper.toMcpCatalogEntry(new McpCatalogEntryDto()));
        assertNotNull(mapper.toPlanConfigDto(new RuntimeConfig.PlanConfig()));
        assertNotNull(mapper.toPlanConfig(new PlanConfigDto()));
        assertNotNull(mapper.toHiveConfigDto(new RuntimeConfig.HiveConfig()));
        assertNotNull(mapper.toHiveConfig(new HiveConfigDto()));
        assertNotNull(mapper.toAutoModeConfig(new AutoModeConfigDto()));
        assertNotNull(mapper.toAutoModeConfigDto(new RuntimeConfig.AutoModeConfig()));
        assertNotNull(mapper.toTracingConfig(new TracingConfigDto()));
        assertNotNull(mapper.toTracingConfigDto(new RuntimeConfig.TracingConfig()));
        assertNotNull(mapper.toRateLimitConfig(new RateLimitConfigDto()));
        assertNotNull(mapper.toSecurityConfig(new SecurityConfigDto()));
        assertNotNull(mapper.toCompactionConfig(new CompactionConfigDto()));
    }

    @Test
    void shouldReturnNullForNullSectionInputs() {
        assertNull(mapper.toModelRouterConfigDto(null));
        assertNull(mapper.toLlmConfig(null));
        assertNull(mapper.toToolsConfigDto(null));
        assertNull(mapper.toVoiceConfig(null));
        assertNull(mapper.toSessionRetentionConfigDto(null));
    }
}
