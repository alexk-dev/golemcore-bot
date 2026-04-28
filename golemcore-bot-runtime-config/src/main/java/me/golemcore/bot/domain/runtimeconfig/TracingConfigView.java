package me.golemcore.bot.domain.runtimeconfig;

import me.golemcore.bot.domain.model.RuntimeConfig;

public interface TracingConfigView {
    RuntimeConfig.TracingConfig getTracingConfig();

    boolean isTracingEnabled();

    int getTraceMaxSnapshotSizeKb();

    int getTraceMaxSnapshotsPerSpan();

    int getTraceMaxTracesPerSession();
}
