package me.golemcore.bot.domain.tracing;

import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.runtimeconfig.TracingConfigView;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TraceRuntimeConfigSupportSelfEvolvingTest {

    @Test
    void shouldForcePayloadSnapshotsWhenSelfEvolvingIsEnabled() {
        TracingConfigView tracingConfigView = tracingConfigView(0.15d, false, false, false, false, false);

        RuntimeConfig.TracingConfig tracingConfig = TraceRuntimeConfigSupport.resolve(tracingConfigView, true);

        assertTrue(tracingConfig.getPayloadSnapshotsEnabled());
        assertTrue(tracingConfig.getCaptureInboundPayloads());
        assertTrue(tracingConfig.getCaptureOutboundPayloads());
        assertTrue(tracingConfig.getCaptureToolPayloads());
        assertTrue(tracingConfig.getCaptureLlmPayloads());
        assertEquals(0.15d, tracingConfig.getResiliencePayloadSampleRate());
    }

    @Test
    void shouldKeepConfiguredPayloadFlagsWhenSelfEvolvingDisabled() {
        TracingConfigView tracingConfigView = tracingConfigView(0.0d, false, false, false, false, false);

        RuntimeConfig.TracingConfig tracingConfig = TraceRuntimeConfigSupport.resolve(tracingConfigView, false);

        assertFalse(tracingConfig.getPayloadSnapshotsEnabled());
        assertFalse(tracingConfig.getCaptureInboundPayloads());
        assertFalse(tracingConfig.getCaptureOutboundPayloads());
        assertFalse(tracingConfig.getCaptureToolPayloads());
        assertFalse(tracingConfig.getCaptureLlmPayloads());
        assertEquals(0.0d, tracingConfig.getResiliencePayloadSampleRate());
    }

    @Test
    void shouldRespectConfiguredPayloadFlagsWhenOverrideIsDisabled() {
        TracingConfigView tracingConfigView = tracingConfigView(0.5d, true, true, false, false, false);

        RuntimeConfig.TracingConfig tracingConfig = TraceRuntimeConfigSupport.resolve(tracingConfigView, false);

        assertTrue(tracingConfig.getPayloadSnapshotsEnabled());
        assertTrue(tracingConfig.getCaptureInboundPayloads());
        assertFalse(tracingConfig.getCaptureOutboundPayloads());
        assertFalse(tracingConfig.getCaptureToolPayloads());
        assertFalse(tracingConfig.getCaptureLlmPayloads());
        assertEquals(0.5d, tracingConfig.getResiliencePayloadSampleRate());
    }

    @Test
    void shouldReturnNullWhenRuntimeConfigServiceIsMissing() {
        assertTrue(TraceRuntimeConfigSupport.resolve(null, false) == null);
    }

    private TracingConfigView tracingConfigView(double resilienceSampleRate, boolean snapshotsEnabled,
            boolean inboundEnabled, boolean outboundEnabled, boolean toolEnabled, boolean llmEnabled) {
        TracingConfigView tracingConfigView = mock(TracingConfigView.class);
        when(tracingConfigView.isTracingEnabled()).thenReturn(true);
        when(tracingConfigView.isPayloadSnapshotsEnabled()).thenReturn(snapshotsEnabled);
        when(tracingConfigView.getSessionTraceBudgetMb()).thenReturn(128);
        when(tracingConfigView.getTraceMaxSnapshotSizeKb()).thenReturn(256);
        when(tracingConfigView.getTraceMaxSnapshotsPerSpan()).thenReturn(10);
        when(tracingConfigView.getTraceMaxTracesPerSession()).thenReturn(100);
        when(tracingConfigView.isTraceInboundPayloadCaptureEnabled()).thenReturn(inboundEnabled);
        when(tracingConfigView.isTraceOutboundPayloadCaptureEnabled()).thenReturn(outboundEnabled);
        when(tracingConfigView.isTraceToolPayloadCaptureEnabled()).thenReturn(toolEnabled);
        when(tracingConfigView.isTraceLlmPayloadCaptureEnabled()).thenReturn(llmEnabled);
        when(tracingConfigView.getTraceResiliencePayloadSampleRate()).thenReturn(resilienceSampleRate);
        return tracingConfigView;
    }
}
