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
import me.golemcore.bot.adapter.inbound.web.dto.settings.RuntimeSettingsWebDtos.RateLimitConfigDto;
import me.golemcore.bot.adapter.inbound.web.dto.settings.RuntimeSettingsWebDtos.RuntimeConfigDto;
import me.golemcore.bot.adapter.inbound.web.dto.settings.RuntimeSettingsWebDtos.SessionRetentionConfigDto;
import me.golemcore.bot.adapter.inbound.web.dto.settings.RuntimeSettingsWebDtos.SecurityConfigDto;
import me.golemcore.bot.adapter.inbound.web.dto.settings.RuntimeSettingsWebDtos.ShellEnvironmentVariableDto;
import me.golemcore.bot.adapter.inbound.web.dto.settings.RuntimeSettingsWebDtos.SkillsConfigDto;
import me.golemcore.bot.adapter.inbound.web.dto.settings.RuntimeSettingsWebDtos.TelemetryConfigDto;
import me.golemcore.bot.adapter.inbound.web.dto.settings.RuntimeSettingsWebDtos.ToolsConfigDto;
import me.golemcore.bot.adapter.inbound.web.dto.settings.RuntimeSettingsWebDtos.TurnConfigDto;
import me.golemcore.bot.adapter.inbound.web.dto.settings.RuntimeSettingsWebDtos.TracingConfigDto;
import me.golemcore.bot.adapter.inbound.web.dto.settings.RuntimeSettingsWebDtos.UsageConfigDto;
import me.golemcore.bot.adapter.inbound.web.dto.settings.RuntimeSettingsWebDtos.VoiceConfigDto;
import me.golemcore.bot.domain.model.RuntimeConfig;
import org.springframework.stereotype.Component;

@Component
public class RuntimeSettingsWebMapper {

    public RuntimeConfigDto toRuntimeConfigDto(RuntimeConfig source) {
        RuntimeConfigDto target = new RuntimeConfigDto();
        copyRuntimeConfig(source, target);
        return target;
    }

    public RuntimeConfig toRuntimeConfig(RuntimeConfigDto source) {
        RuntimeConfig target = RuntimeConfig.builder().build();
        copyRuntimeConfig(source, target);
        return target;
    }

    public ModelRouterConfigDto toModelRouterConfigDto(RuntimeConfig.ModelRouterConfig source) {
        return copy(source, new ModelRouterConfigDto());
    }

    public RuntimeConfig.ModelRouterConfig toModelRouterConfig(ModelRouterConfigDto source) {
        return copy(source, new RuntimeConfig.ModelRouterConfig());
    }

    public LlmConfigDto toLlmConfigDto(RuntimeConfig.LlmConfig source) {
        return copy(source, new LlmConfigDto());
    }

    public RuntimeConfig.LlmConfig toLlmConfig(LlmConfigDto source) {
        return copy(source, new RuntimeConfig.LlmConfig());
    }

    public LlmProviderConfigDto toLlmProviderConfigDto(RuntimeConfig.LlmProviderConfig source) {
        return copy(source, new LlmProviderConfigDto());
    }

    public RuntimeConfig.LlmProviderConfig toLlmProviderConfig(LlmProviderConfigDto source) {
        return copy(source, new RuntimeConfig.LlmProviderConfig());
    }

    public ToolsConfigDto toToolsConfigDto(RuntimeConfig.ToolsConfig source) {
        return copy(source, new ToolsConfigDto());
    }

    public RuntimeConfig.ToolsConfig toToolsConfig(ToolsConfigDto source) {
        return copy(source, new RuntimeConfig.ToolsConfig());
    }

    public ShellEnvironmentVariableDto toShellEnvironmentVariableDto(RuntimeConfig.ShellEnvironmentVariable source) {
        return copy(source, new ShellEnvironmentVariableDto());
    }

    public RuntimeConfig.ShellEnvironmentVariable toShellEnvironmentVariable(ShellEnvironmentVariableDto source) {
        return copy(source, new RuntimeConfig.ShellEnvironmentVariable());
    }

    public VoiceConfigDto toVoiceConfigDto(RuntimeConfig.VoiceConfig source) {
        return copy(source, new VoiceConfigDto());
    }

    public RuntimeConfig.VoiceConfig toVoiceConfig(VoiceConfigDto source) {
        return copy(source, new RuntimeConfig.VoiceConfig());
    }

    public TurnConfigDto toTurnConfigDto(RuntimeConfig.TurnConfig source) {
        return copy(source, new TurnConfigDto());
    }

    public RuntimeConfig.TurnConfig toTurnConfig(TurnConfigDto source) {
        return copy(source, new RuntimeConfig.TurnConfig());
    }

    public SessionRetentionConfigDto toSessionRetentionConfigDto(RuntimeConfig.SessionRetentionConfig source) {
        return copy(source, new SessionRetentionConfigDto());
    }

    public RuntimeConfig.SessionRetentionConfig toSessionRetentionConfig(SessionRetentionConfigDto source) {
        return copy(source, new RuntimeConfig.SessionRetentionConfig());
    }

    public MemoryConfigDto toMemoryConfigDto(RuntimeConfig.MemoryConfig source) {
        return copy(source, new MemoryConfigDto());
    }

    public RuntimeConfig.MemoryConfig toMemoryConfig(MemoryConfigDto source) {
        return copy(source, new RuntimeConfig.MemoryConfig());
    }

    public SkillsConfigDto toSkillsConfigDto(RuntimeConfig.SkillsConfig source) {
        return copy(source, new SkillsConfigDto());
    }

    public RuntimeConfig.SkillsConfig toSkillsConfig(SkillsConfigDto source) {
        return copy(source, new RuntimeConfig.SkillsConfig());
    }

    public UsageConfigDto toUsageConfigDto(RuntimeConfig.UsageConfig source) {
        return copy(source, new UsageConfigDto());
    }

    public RuntimeConfig.UsageConfig toUsageConfig(UsageConfigDto source) {
        return copy(source, new RuntimeConfig.UsageConfig());
    }

    public TelemetryConfigDto toTelemetryConfigDto(RuntimeConfig.TelemetryConfig source) {
        return copy(source, new TelemetryConfigDto());
    }

    public RuntimeConfig.TelemetryConfig toTelemetryConfig(TelemetryConfigDto source) {
        return copy(source, new RuntimeConfig.TelemetryConfig());
    }

    public McpConfigDto toMcpConfigDto(RuntimeConfig.McpConfig source) {
        return copy(source, new McpConfigDto());
    }

    public RuntimeConfig.McpConfig toMcpConfig(McpConfigDto source) {
        return copy(source, new RuntimeConfig.McpConfig());
    }

    public McpCatalogEntryDto toMcpCatalogEntryDto(RuntimeConfig.McpCatalogEntry source) {
        return copy(source, new McpCatalogEntryDto());
    }

    public RuntimeConfig.McpCatalogEntry toMcpCatalogEntry(McpCatalogEntryDto source) {
        return copy(source, new RuntimeConfig.McpCatalogEntry());
    }

    public HiveConfigDto toHiveConfigDto(RuntimeConfig.HiveConfig source) {
        return copy(source, new HiveConfigDto());
    }

    public RuntimeConfig.HiveConfig toHiveConfig(HiveConfigDto source) {
        return copy(source, new RuntimeConfig.HiveConfig());
    }

    public RuntimeConfig.PlanConfig toPlanConfig(
            me.golemcore.bot.adapter.inbound.web.dto.settings.RuntimeSettingsWebDtos.PlanConfigDto source) {
        return copy(source, new RuntimeConfig.PlanConfig());
    }

    public me.golemcore.bot.adapter.inbound.web.dto.settings.RuntimeSettingsWebDtos.PlanConfigDto toPlanConfigDto(
            RuntimeConfig.PlanConfig source) {
        return copy(source,
                new me.golemcore.bot.adapter.inbound.web.dto.settings.RuntimeSettingsWebDtos.PlanConfigDto());
    }

    public RuntimeConfig.AutoModeConfig toAutoModeConfig(AutoModeConfigDto source) {
        return copy(source, new RuntimeConfig.AutoModeConfig());
    }

    public AutoModeConfigDto toAutoModeConfigDto(RuntimeConfig.AutoModeConfig source) {
        return copy(source, new AutoModeConfigDto());
    }

    public RuntimeConfig.TracingConfig toTracingConfig(TracingConfigDto source) {
        return copy(source, new RuntimeConfig.TracingConfig());
    }

    public TracingConfigDto toTracingConfigDto(RuntimeConfig.TracingConfig source) {
        return copy(source, new TracingConfigDto());
    }

    public RuntimeConfig.RateLimitConfig toRateLimitConfig(RateLimitConfigDto source) {
        return copy(source, new RuntimeConfig.RateLimitConfig());
    }

    public RuntimeConfig.SecurityConfig toSecurityConfig(SecurityConfigDto source) {
        return copy(source, new RuntimeConfig.SecurityConfig());
    }

    public RuntimeConfig.CompactionConfig toCompactionConfig(CompactionConfigDto source) {
        return copy(source, new RuntimeConfig.CompactionConfig());
    }

    private void copyRuntimeConfig(RuntimeConfig source, RuntimeConfig target) {
        if (source == null || target == null) {
            return;
        }
        target.setTelegram(source.getTelegram());
        target.setModelRouter(source.getModelRouter());
        target.setLlm(source.getLlm());
        target.setTools(source.getTools());
        target.setVoice(source.getVoice());
        target.setAutoMode(source.getAutoMode());
        target.setUpdate(source.getUpdate());
        target.setTracing(source.getTracing());
        target.setRateLimit(source.getRateLimit());
        target.setSecurity(source.getSecurity());
        target.setCompaction(source.getCompaction());
        target.setTurn(source.getTurn());
        target.setSessionRetention(source.getSessionRetention());
        target.setMemory(source.getMemory());
        target.setSkills(source.getSkills());
        target.setModelRegistry(source.getModelRegistry());
        target.setUsage(source.getUsage());
        target.setTelemetry(source.getTelemetry());
        target.setMcp(source.getMcp());
        target.setPlan(source.getPlan());
        target.setDelayedActions(source.getDelayedActions());
        target.setHive(source.getHive());
        target.setSelfEvolving(source.getSelfEvolving());
    }

    private <S, T> T copy(S source, T target) {
        if (source == null) {
            return null;
        }
        try {
            java.beans.BeanInfo beanInfo = java.beans.Introspector.getBeanInfo(source.getClass(), Object.class);
            for (java.beans.PropertyDescriptor descriptor : beanInfo.getPropertyDescriptors()) {
                java.lang.reflect.Method readMethod = descriptor.getReadMethod();
                java.lang.reflect.Method writeMethod = findWriteMethod(target.getClass(), descriptor.getName(),
                        descriptor.getPropertyType());
                if (readMethod != null && writeMethod != null) {
                    Object value = readMethod.invoke(source);
                    writeMethod.invoke(target, value);
                }
            }
            return target;
        } catch (java.beans.IntrospectionException | ReflectiveOperationException exception) {
            throw new IllegalStateException("Failed to map runtime settings DTO", exception);
        }
    }

    private java.lang.reflect.Method findWriteMethod(Class<?> targetClass, String propertyName, Class<?> propertyType) {
        try {
            String setterName = "set" + Character.toUpperCase(propertyName.charAt(0)) + propertyName.substring(1);
            return targetClass.getMethod(setterName, propertyType);
        } catch (NoSuchMethodException exception) {
            return null;
        }
    }
}
