package me.golemcore.bot.port.outbound;

import me.golemcore.bot.domain.model.RuntimeConfig;

public interface ManagedPolicyRuntimeConfigPort {

    RuntimeConfig snapshotRuntimeConfig();

    void replaceManagedPolicySections(RuntimeConfig.LlmConfig llmConfig,
            RuntimeConfig.ModelRouterConfig modelRouterConfig);

    void restoreRuntimeConfigSnapshot(RuntimeConfig snapshot);
}
