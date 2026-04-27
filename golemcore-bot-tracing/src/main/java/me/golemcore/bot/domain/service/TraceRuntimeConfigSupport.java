package me.golemcore.bot.domain.service;

import me.golemcore.bot.domain.model.RuntimeConfig;

public final class TraceRuntimeConfigSupport {

    private TraceRuntimeConfigSupport() {
    }

    public static RuntimeConfig.TracingConfig resolve(RuntimeConfigService runtimeConfigService) {
        if (runtimeConfigService == null) {
            return null;
        }
        boolean forcePayloadCapture = runtimeConfigService.isSelfEvolvingEnabled()
                && runtimeConfigService.isSelfEvolvingTracePayloadOverrideEnabled();
        RuntimeConfig.TracingConfig tracingConfig = new RuntimeConfig.TracingConfig();
        tracingConfig.setEnabled(runtimeConfigService.isTracingEnabled());
        tracingConfig
                .setPayloadSnapshotsEnabled(runtimeConfigService.isPayloadSnapshotsEnabled() || forcePayloadCapture);
        tracingConfig.setSessionTraceBudgetMb(runtimeConfigService.getSessionTraceBudgetMb());
        tracingConfig.setMaxSnapshotSizeKb(runtimeConfigService.getTraceMaxSnapshotSizeKb());
        tracingConfig.setMaxSnapshotsPerSpan(runtimeConfigService.getTraceMaxSnapshotsPerSpan());
        tracingConfig.setMaxTracesPerSession(runtimeConfigService.getTraceMaxTracesPerSession());
        tracingConfig.setCaptureInboundPayloads(
                runtimeConfigService.isTraceInboundPayloadCaptureEnabled() || forcePayloadCapture);
        tracingConfig.setCaptureOutboundPayloads(
                runtimeConfigService.isTraceOutboundPayloadCaptureEnabled() || forcePayloadCapture);
        tracingConfig
                .setCaptureToolPayloads(runtimeConfigService.isTraceToolPayloadCaptureEnabled() || forcePayloadCapture);
        tracingConfig
                .setCaptureLlmPayloads(runtimeConfigService.isTraceLlmPayloadCaptureEnabled() || forcePayloadCapture);
        tracingConfig.setResiliencePayloadSampleRate(runtimeConfigService.getTraceResiliencePayloadSampleRate());
        return tracingConfig;
    }
}
