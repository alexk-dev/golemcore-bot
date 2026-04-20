package me.golemcore.bot.port.outbound;

import me.golemcore.bot.domain.model.RuntimeConfig;

/**
 * Query-side runtime config access used by the self-evolving capability.
 */
public interface SelfEvolvingRuntimeConfigPort {

    RuntimeConfig.SelfEvolvingConfig getSelfEvolvingConfig();

    boolean isSelfEvolvingEnabled();

    boolean isSelfEvolvingTracePayloadOverrideEnabled();

    String getSelfEvolvingJudgePrimaryTier();

    String getSelfEvolvingJudgeTiebreakerTier();

    String getSelfEvolvingJudgeEvolutionTier();

    String getSelfEvolvingPromotionMode();

    boolean isSelfEvolvingPromotionShadowRequired();

    boolean isSelfEvolvingPromotionCanaryRequired();

    boolean isTacticQueryExpansionEnabled();

    String getTacticQueryExpansionTier();

    int getTacticAdvisoryCount();

    boolean isTracingEnabled();

    boolean isPayloadSnapshotsEnabled();

    int getSessionTraceBudgetMb();

    int getTraceMaxSnapshotSizeKb();

    int getTraceMaxSnapshotsPerSpan();

    int getTraceMaxTracesPerSession();

    boolean isTraceInboundPayloadCaptureEnabled();

    boolean isTraceOutboundPayloadCaptureEnabled();

    boolean isTraceToolPayloadCaptureEnabled();

    boolean isTraceLlmPayloadCaptureEnabled();

    default RuntimeConfig.TracingConfig getEffectiveTracingConfig() {
        boolean forcePayloadCapture = isSelfEvolvingEnabled() && isSelfEvolvingTracePayloadOverrideEnabled();
        RuntimeConfig.TracingConfig tracingConfig = new RuntimeConfig.TracingConfig();
        tracingConfig.setEnabled(isTracingEnabled());
        tracingConfig.setPayloadSnapshotsEnabled(isPayloadSnapshotsEnabled() || forcePayloadCapture);
        tracingConfig.setSessionTraceBudgetMb(getSessionTraceBudgetMb());
        tracingConfig.setMaxSnapshotSizeKb(getTraceMaxSnapshotSizeKb());
        tracingConfig.setMaxSnapshotsPerSpan(getTraceMaxSnapshotsPerSpan());
        tracingConfig.setMaxTracesPerSession(getTraceMaxTracesPerSession());
        tracingConfig.setCaptureInboundPayloads(isTraceInboundPayloadCaptureEnabled() || forcePayloadCapture);
        tracingConfig.setCaptureOutboundPayloads(isTraceOutboundPayloadCaptureEnabled() || forcePayloadCapture);
        tracingConfig.setCaptureToolPayloads(isTraceToolPayloadCaptureEnabled() || forcePayloadCapture);
        tracingConfig.setCaptureLlmPayloads(isTraceLlmPayloadCaptureEnabled() || forcePayloadCapture);
        return tracingConfig;
    }
}
