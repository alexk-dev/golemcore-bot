package me.golemcore.bot.domain.runtimeconfig;

import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_TRACING_CAPTURE_INBOUND_PAYLOADS;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_TRACING_CAPTURE_LLM_PAYLOADS;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_TRACING_CAPTURE_OUTBOUND_PAYLOADS;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_TRACING_CAPTURE_TOOL_PAYLOADS;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_TRACING_ENABLED;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_TRACING_MAX_SNAPSHOT_SIZE_KB;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_TRACING_MAX_SNAPSHOTS_PER_SPAN;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_TRACING_MAX_TRACES_PER_SESSION;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_TRACING_PAYLOAD_SNAPSHOTS_ENABLED;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_TRACING_RESILIENCE_PAYLOAD_SAMPLE_RATE;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_TRACING_SESSION_TRACE_BUDGET_MB;

import me.golemcore.bot.domain.model.RuntimeConfig;

public interface TracingConfigView extends RuntimeConfigSource {
    default RuntimeConfig.TracingConfig getTracingConfig() {
        return getRuntimeConfig().getTracing();
    }

    default boolean isTracingEnabled() {
        RuntimeConfig.TracingConfig tracingConfig = getRuntimeConfig().getTracing();
        if (tracingConfig == null) {
            return DEFAULT_TRACING_ENABLED;
        }
        Boolean value = tracingConfig.getEnabled();
        return value != null ? value : DEFAULT_TRACING_ENABLED;
    }

    default boolean isPayloadSnapshotsEnabled() {
        RuntimeConfig.TracingConfig tracingConfig = getRuntimeConfig().getTracing();
        if (tracingConfig == null) {
            return DEFAULT_TRACING_PAYLOAD_SNAPSHOTS_ENABLED;
        }
        Boolean value = tracingConfig.getPayloadSnapshotsEnabled();
        return value != null ? value : DEFAULT_TRACING_PAYLOAD_SNAPSHOTS_ENABLED;
    }

    default int getSessionTraceBudgetMb() {
        RuntimeConfig.TracingConfig tracingConfig = getRuntimeConfig().getTracing();
        if (tracingConfig == null || tracingConfig.getSessionTraceBudgetMb() == null) {
            return DEFAULT_TRACING_SESSION_TRACE_BUDGET_MB;
        }
        return tracingConfig.getSessionTraceBudgetMb();
    }

    default int getTraceMaxSnapshotSizeKb() {
        RuntimeConfig.TracingConfig tracingConfig = getRuntimeConfig().getTracing();
        if (tracingConfig == null || tracingConfig.getMaxSnapshotSizeKb() == null) {
            return DEFAULT_TRACING_MAX_SNAPSHOT_SIZE_KB;
        }
        return tracingConfig.getMaxSnapshotSizeKb();
    }

    default int getTraceMaxSnapshotsPerSpan() {
        RuntimeConfig.TracingConfig tracingConfig = getRuntimeConfig().getTracing();
        if (tracingConfig == null || tracingConfig.getMaxSnapshotsPerSpan() == null) {
            return DEFAULT_TRACING_MAX_SNAPSHOTS_PER_SPAN;
        }
        return tracingConfig.getMaxSnapshotsPerSpan();
    }

    default int getTraceMaxTracesPerSession() {
        RuntimeConfig.TracingConfig tracingConfig = getRuntimeConfig().getTracing();
        if (tracingConfig == null || tracingConfig.getMaxTracesPerSession() == null) {
            return DEFAULT_TRACING_MAX_TRACES_PER_SESSION;
        }
        return tracingConfig.getMaxTracesPerSession();
    }

    default boolean isTraceInboundPayloadCaptureEnabled() {
        RuntimeConfig.TracingConfig tracingConfig = getRuntimeConfig().getTracing();
        if (tracingConfig == null) {
            return DEFAULT_TRACING_CAPTURE_INBOUND_PAYLOADS;
        }
        Boolean value = tracingConfig.getCaptureInboundPayloads();
        return value != null ? value : DEFAULT_TRACING_CAPTURE_INBOUND_PAYLOADS;
    }

    default boolean isTraceOutboundPayloadCaptureEnabled() {
        RuntimeConfig.TracingConfig tracingConfig = getRuntimeConfig().getTracing();
        if (tracingConfig == null) {
            return DEFAULT_TRACING_CAPTURE_OUTBOUND_PAYLOADS;
        }
        Boolean value = tracingConfig.getCaptureOutboundPayloads();
        return value != null ? value : DEFAULT_TRACING_CAPTURE_OUTBOUND_PAYLOADS;
    }

    default boolean isTraceToolPayloadCaptureEnabled() {
        RuntimeConfig.TracingConfig tracingConfig = getRuntimeConfig().getTracing();
        if (tracingConfig == null) {
            return DEFAULT_TRACING_CAPTURE_TOOL_PAYLOADS;
        }
        Boolean value = tracingConfig.getCaptureToolPayloads();
        return value != null ? value : DEFAULT_TRACING_CAPTURE_TOOL_PAYLOADS;
    }

    default boolean isTraceLlmPayloadCaptureEnabled() {
        RuntimeConfig.TracingConfig tracingConfig = getRuntimeConfig().getTracing();
        if (tracingConfig == null) {
            return DEFAULT_TRACING_CAPTURE_LLM_PAYLOADS;
        }
        Boolean value = tracingConfig.getCaptureLlmPayloads();
        return value != null ? value : DEFAULT_TRACING_CAPTURE_LLM_PAYLOADS;
    }

    default double getTraceResiliencePayloadSampleRate() {
        RuntimeConfig.TracingConfig tracingConfig = getRuntimeConfig().getTracing();
        if (tracingConfig == null || tracingConfig.getResiliencePayloadSampleRate() == null) {
            return DEFAULT_TRACING_RESILIENCE_PAYLOAD_SAMPLE_RATE;
        }
        Double value = tracingConfig.getResiliencePayloadSampleRate();
        if (value.isNaN()) {
            return DEFAULT_TRACING_RESILIENCE_PAYLOAD_SAMPLE_RATE;
        }
        return Math.max(0.0d, Math.min(1.0d, value.doubleValue()));
    }
}
