package me.golemcore.bot.application.selfevolving.tactic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.model.Secret;
import me.golemcore.bot.port.outbound.SelfEvolvingRuntimeConfigPort;
import me.golemcore.bot.port.outbound.EmbeddingClientResolverPort;
import me.golemcore.bot.port.outbound.EmbeddingPort;
import me.golemcore.bot.port.outbound.EmbeddingProviderIds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TacticEmbeddingProbeServiceTest {

    private EmbeddingClientResolverPort embeddingClientResolverPort;
    private SelfEvolvingRuntimeConfigPort runtimeConfigService;
    private EmbeddingPort embeddingPort;
    private TacticEmbeddingProbeService service;

    @BeforeEach
    void setUp() {
        embeddingClientResolverPort = mock(EmbeddingClientResolverPort.class);
        runtimeConfigService = mock(SelfEvolvingRuntimeConfigPort.class);
        embeddingPort = mock(EmbeddingPort.class);
        service = new TacticEmbeddingProbeService(embeddingClientResolverPort, runtimeConfigService);
    }

    @Test
    void shouldUseStoredApiKeyFallbackWhenRequestApiKeyMissing() {
        RuntimeConfig.SelfEvolvingTacticEmbeddingsConfig embeddings = RuntimeConfig.SelfEvolvingTacticEmbeddingsConfig
                .builder()
                .apiKey(Secret.of("stored-key"))
                .build();
        RuntimeConfig.SelfEvolvingConfig config = RuntimeConfig.SelfEvolvingConfig.builder()
                .tactics(RuntimeConfig.SelfEvolvingTacticsConfig.builder()
                        .search(RuntimeConfig.SelfEvolvingTacticSearchConfig.builder()
                                .embeddings(embeddings)
                                .build())
                        .build())
                .build();
        when(runtimeConfigService.getSelfEvolvingConfig()).thenReturn(config);
        when(embeddingClientResolverPort.resolve(EmbeddingProviderIds.OPENAI_COMPATIBLE)).thenReturn(embeddingPort);
        when(embeddingPort.embed(org.mockito.ArgumentMatchers.any())).thenReturn(
                new EmbeddingPort.EmbeddingResponse("text-embedding-3-large", List.of(List.of(0.1d, 0.2d, 0.3d))));

        TacticEmbeddingProbeService.EmbeddingProbeResult result = service.probe(
                new TacticEmbeddingProbeService.ProbeRequest(
                        null,
                        null,
                        null,
                        null,
                        null));

        assertTrue(result.ok());
        assertEquals("text-embedding-3-large", result.model());
        assertEquals(3, result.dimensions());
        assertEquals(3, result.vectorLength());
        assertEquals("https://api.openai.com/v1", result.baseUrl());
        assertNull(result.error());
        verify(embeddingClientResolverPort).resolve(EmbeddingProviderIds.OPENAI_COMPATIBLE);
        verify(embeddingPort).embed(
                org.mockito.ArgumentMatchers.argThat(request -> "https://api.openai.com/v1".equals(request.baseUrl())
                        && "stored-key".equals(request.apiKey())
                        && "text-embedding-3-large".equals(request.model())
                        && request.dimensions() == null
                        && request.timeoutMs() == null
                        && request.inputs().equals(List.of("golemcore embedding connectivity check"))));
    }

    @Test
    void shouldSurfaceFailuresWithoutHidingContext() {
        when(runtimeConfigService.getSelfEvolvingConfig())
                .thenReturn(RuntimeConfig.SelfEvolvingConfig.builder().build());
        when(embeddingClientResolverPort.resolve(EmbeddingProviderIds.OPENAI_COMPATIBLE)).thenReturn(embeddingPort);
        when(embeddingPort.embed(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new RuntimeException("connect failed", new IllegalStateException("timeout")));

        TacticEmbeddingProbeService.EmbeddingProbeResult result = service.probe(
                new TacticEmbeddingProbeService.ProbeRequest(
                        "https://example.invalid/v1",
                        "  request-key  ",
                        "probe-model",
                        1536,
                        2000));

        assertFalse(result.ok());
        assertEquals("probe-model", result.model());
        assertEquals(1536, result.dimensions());
        assertNull(result.vectorLength());
        assertEquals("https://example.invalid/v1", result.baseUrl());
        assertEquals("connect failed (timeout)", result.error());
        verify(embeddingClientResolverPort).resolve(EmbeddingProviderIds.OPENAI_COMPATIBLE);
    }
}
