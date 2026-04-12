package me.golemcore.bot.domain.service;

import me.golemcore.bot.domain.model.RuntimeConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TraceRuntimeConfigSupportSelfEvolvingTest {

    @Test
    void shouldForcePayloadSnapshotsWhenSelfEvolvingIsEnabled() {
        RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);
        when(runtimeConfigService.isTracingEnabled()).thenReturn(true);
        when(runtimeConfigService.isPayloadSnapshotsEnabled()).thenReturn(false);
        when(runtimeConfigService.getSessionTraceBudgetMb()).thenReturn(128);
        when(runtimeConfigService.getTraceMaxSnapshotSizeKb()).thenReturn(256);
        when(runtimeConfigService.getTraceMaxSnapshotsPerSpan()).thenReturn(10);
        when(runtimeConfigService.getTraceMaxTracesPerSession()).thenReturn(100);
        when(runtimeConfigService.isTraceInboundPayloadCaptureEnabled()).thenReturn(false);
        when(runtimeConfigService.isTraceOutboundPayloadCaptureEnabled()).thenReturn(false);
        when(runtimeConfigService.isTraceToolPayloadCaptureEnabled()).thenReturn(false);
        when(runtimeConfigService.isTraceLlmPayloadCaptureEnabled()).thenReturn(false);
        when(runtimeConfigService.isSelfEvolvingEnabled()).thenReturn(true);
        when(runtimeConfigService.isSelfEvolvingTracePayloadOverrideEnabled()).thenReturn(true);

        RuntimeConfig.TracingConfig tracingConfig = TraceRuntimeConfigSupport.resolve(runtimeConfigService);

        assertTrue(tracingConfig.getPayloadSnapshotsEnabled());
        assertTrue(tracingConfig.getCaptureInboundPayloads());
        assertTrue(tracingConfig.getCaptureOutboundPayloads());
        assertTrue(tracingConfig.getCaptureToolPayloads());
        assertTrue(tracingConfig.getCaptureLlmPayloads());
    }

    @Test
    void shouldKeepConfiguredPayloadFlagsWhenSelfEvolvingDisabled() {
        RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);
        when(runtimeConfigService.isTracingEnabled()).thenReturn(true);
        when(runtimeConfigService.isPayloadSnapshotsEnabled()).thenReturn(false);
        when(runtimeConfigService.getSessionTraceBudgetMb()).thenReturn(128);
        when(runtimeConfigService.getTraceMaxSnapshotSizeKb()).thenReturn(256);
        when(runtimeConfigService.getTraceMaxSnapshotsPerSpan()).thenReturn(10);
        when(runtimeConfigService.getTraceMaxTracesPerSession()).thenReturn(100);
        when(runtimeConfigService.isTraceInboundPayloadCaptureEnabled()).thenReturn(false);
        when(runtimeConfigService.isTraceOutboundPayloadCaptureEnabled()).thenReturn(false);
        when(runtimeConfigService.isTraceToolPayloadCaptureEnabled()).thenReturn(false);
        when(runtimeConfigService.isTraceLlmPayloadCaptureEnabled()).thenReturn(false);
        when(runtimeConfigService.isSelfEvolvingEnabled()).thenReturn(false);
        when(runtimeConfigService.isSelfEvolvingTracePayloadOverrideEnabled()).thenReturn(true);

        RuntimeConfig.TracingConfig tracingConfig = TraceRuntimeConfigSupport.resolve(runtimeConfigService);

        assertFalse(tracingConfig.getPayloadSnapshotsEnabled());
        assertFalse(tracingConfig.getCaptureInboundPayloads());
        assertFalse(tracingConfig.getCaptureOutboundPayloads());
        assertFalse(tracingConfig.getCaptureToolPayloads());
        assertFalse(tracingConfig.getCaptureLlmPayloads());
    }

    @Test
    void shouldRespectConfiguredPayloadFlagsWhenOverrideIsDisabled() {
        RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);
        when(runtimeConfigService.isTracingEnabled()).thenReturn(true);
        when(runtimeConfigService.isPayloadSnapshotsEnabled()).thenReturn(true);
        when(runtimeConfigService.getSessionTraceBudgetMb()).thenReturn(128);
        when(runtimeConfigService.getTraceMaxSnapshotSizeKb()).thenReturn(256);
        when(runtimeConfigService.getTraceMaxSnapshotsPerSpan()).thenReturn(10);
        when(runtimeConfigService.getTraceMaxTracesPerSession()).thenReturn(100);
        when(runtimeConfigService.isTraceInboundPayloadCaptureEnabled()).thenReturn(true);
        when(runtimeConfigService.isTraceOutboundPayloadCaptureEnabled()).thenReturn(false);
        when(runtimeConfigService.isTraceToolPayloadCaptureEnabled()).thenReturn(false);
        when(runtimeConfigService.isTraceLlmPayloadCaptureEnabled()).thenReturn(false);
        when(runtimeConfigService.isSelfEvolvingEnabled()).thenReturn(true);
        when(runtimeConfigService.isSelfEvolvingTracePayloadOverrideEnabled()).thenReturn(false);

        RuntimeConfig.TracingConfig tracingConfig = TraceRuntimeConfigSupport.resolve(runtimeConfigService);

        assertTrue(tracingConfig.getPayloadSnapshotsEnabled());
        assertTrue(tracingConfig.getCaptureInboundPayloads());
        assertFalse(tracingConfig.getCaptureOutboundPayloads());
        assertFalse(tracingConfig.getCaptureToolPayloads());
        assertFalse(tracingConfig.getCaptureLlmPayloads());
    }

    @Test
    void shouldReturnNullWhenRuntimeConfigServiceIsMissing() {
        assertTrue(TraceRuntimeConfigSupport.resolve(null) == null);
    }
}
