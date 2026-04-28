package me.golemcore.bot.domain.tracing;

import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.runtimeconfig.TracingConfigView;

public final class TraceRuntimeConfigSupport {

    private TraceRuntimeConfigSupport() {
    }

    public static RuntimeConfig.TracingConfig resolve(TracingConfigView tracingConfigView,
            boolean forcePayloadCapture) {
        if (tracingConfigView == null) {
            return null;
        }
        RuntimeConfig.TracingConfig tracingConfig = new RuntimeConfig.TracingConfig();
        tracingConfig.setEnabled(tracingConfigView.isTracingEnabled());
        tracingConfig.setPayloadSnapshotsEnabled(tracingConfigView.isPayloadSnapshotsEnabled() || forcePayloadCapture);
        tracingConfig.setSessionTraceBudgetMb(tracingConfigView.getSessionTraceBudgetMb());
        tracingConfig.setMaxSnapshotSizeKb(tracingConfigView.getTraceMaxSnapshotSizeKb());
        tracingConfig.setMaxSnapshotsPerSpan(tracingConfigView.getTraceMaxSnapshotsPerSpan());
        tracingConfig.setMaxTracesPerSession(tracingConfigView.getTraceMaxTracesPerSession());
        tracingConfig.setCaptureInboundPayloads(
                tracingConfigView.isTraceInboundPayloadCaptureEnabled() || forcePayloadCapture);
        tracingConfig.setCaptureOutboundPayloads(
                tracingConfigView.isTraceOutboundPayloadCaptureEnabled() || forcePayloadCapture);
        tracingConfig
                .setCaptureToolPayloads(tracingConfigView.isTraceToolPayloadCaptureEnabled() || forcePayloadCapture);
        tracingConfig.setCaptureLlmPayloads(tracingConfigView.isTraceLlmPayloadCaptureEnabled() || forcePayloadCapture);
        tracingConfig.setResiliencePayloadSampleRate(tracingConfigView.getTraceResiliencePayloadSampleRate());
        return tracingConfig;
    }
}
