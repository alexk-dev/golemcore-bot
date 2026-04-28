package me.golemcore.bot.domain.runtimeconfig;

import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_TOOL_CONFIRMATION_ENABLED;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_TOOL_CONFIRMATION_TIMEOUT_SECONDS;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import me.golemcore.bot.domain.model.RuntimeConfig;

public interface ToolRuntimeConfigView extends RuntimeConfigSource {
    default boolean isFilesystemEnabled() {
        Boolean val = getRuntimeConfig().getTools().getFilesystemEnabled();
        return val != null ? val : true;
    }

    default boolean isShellEnabled() {
        Boolean val = getRuntimeConfig().getTools().getShellEnabled();
        return val != null ? val : true;
    }

    default Map<String, String> getShellEnvironmentVariables() {
        List<RuntimeConfig.ShellEnvironmentVariable> configured = getRuntimeConfig().getTools()
                .getShellEnvironmentVariables();
        if (configured == null || configured.isEmpty()) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (RuntimeConfig.ShellEnvironmentVariable variable : configured) {
            if (variable == null || variable.getName() == null || variable.getName().isBlank()) {
                continue;
            }
            String name = variable.getName().trim();
            String value = variable.getValue() != null ? variable.getValue() : "";
            result.put(name, value);
        }
        return result;
    }

    default boolean isSkillManagementEnabled() {
        Boolean val = getRuntimeConfig().getTools().getSkillManagementEnabled();
        return val != null ? val : true;
    }

    default boolean isSkillTransitionEnabled() {
        Boolean val = getRuntimeConfig().getTools().getSkillTransitionEnabled();
        return val != null ? val : true;
    }

    default boolean isTierToolEnabled() {
        Boolean val = getRuntimeConfig().getTools().getTierEnabled();
        return val != null ? val : true;
    }

    default boolean isGoalManagementEnabled() {
        Boolean val = getRuntimeConfig().getTools().getGoalManagementEnabled();
        return val != null ? val : true;
    }

    default boolean isToolConfirmationEnabled() {
        Boolean val = getRuntimeConfig().getSecurity().getToolConfirmationEnabled();
        return val != null ? val : DEFAULT_TOOL_CONFIRMATION_ENABLED;
    }

    default int getToolConfirmationTimeoutSeconds() {
        Integer val = getRuntimeConfig().getSecurity().getToolConfirmationTimeoutSeconds();
        return val != null ? val : DEFAULT_TOOL_CONFIRMATION_TIMEOUT_SECONDS;
    }
}
