package me.golemcore.bot.domain.service;

import me.golemcore.bot.domain.model.RuntimeConfig;

public final class TraceRuntimeConfigSupport {

    private TraceRuntimeConfigSupport() {
    }

    public static RuntimeConfig.TracingConfig resolve(RuntimeConfigService runtimeConfigService) {
        if (runtimeConfigService == null) {
            return null;
        }
        RuntimeConfig.TracingConfig tracingConfig = new RuntimeConfig.TracingConfig();
        tracingConfig.setEnabled(runtimeConfigService.isTracingEnabled());
        tracingConfig.setPayloadSnapshotsEnabled(runtimeConfigService.isPayloadSnapshotsEnabled());
        tracingConfig.setSessionTraceBudgetMb(runtimeConfigService.getSessionTraceBudgetMb());
        tracingConfig.setMaxSnapshotSizeKb(runtimeConfigService.getTraceMaxSnapshotSizeKb());
        tracingConfig.setMaxSnapshotsPerSpan(runtimeConfigService.getTraceMaxSnapshotsPerSpan());
        tracingConfig.setMaxTracesPerSession(runtimeConfigService.getTraceMaxTracesPerSession());
        tracingConfig.setCaptureInboundPayloads(runtimeConfigService.isTraceInboundPayloadCaptureEnabled());
        tracingConfig.setCaptureOutboundPayloads(runtimeConfigService.isTraceOutboundPayloadCaptureEnabled());
        tracingConfig.setCaptureToolPayloads(runtimeConfigService.isTraceToolPayloadCaptureEnabled());
        tracingConfig.setCaptureLlmPayloads(runtimeConfigService.isTraceLlmPayloadCaptureEnabled());
        return tracingConfig;
    }
}
