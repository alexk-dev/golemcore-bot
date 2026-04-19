package me.golemcore.bot.adapter.inbound.web.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import me.golemcore.bot.application.selfevolving.tactic.TacticEmbeddingProbeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

class TacticEmbeddingProbeControllerTest {

    private TacticEmbeddingProbeService tacticEmbeddingProbeService;
    private TacticEmbeddingProbeController controller;

    @BeforeEach
    void setUp() {
        tacticEmbeddingProbeService = mock(TacticEmbeddingProbeService.class);
        controller = new TacticEmbeddingProbeController(tacticEmbeddingProbeService);
    }

    @Test
    void shouldDelegateEmbeddingProbeAndMapResponse() {
        when(tacticEmbeddingProbeService.probe(new TacticEmbeddingProbeService.ProbeRequest(
                "https://example.invalid/v1",
                "request-key",
                "probe-model",
                1536,
                2000))).thenReturn(new TacticEmbeddingProbeService.EmbeddingProbeResult(
                        true,
                        "text-embedding-3-large",
                        3,
                        3,
                        "https://example.invalid/v1",
                        null));

        StepVerifier.create(controller.probeRemoteEmbedding(new TacticEmbeddingProbeController.ProbeRequest(
                "https://example.invalid/v1",
                "request-key",
                "probe-model",
                1536,
                2000)))
                .assertNext(response -> {
                    assertEquals(200, response.getStatusCode().value());
                    assertEquals("text-embedding-3-large", response.getBody().model());
                    assertEquals(3, response.getBody().dimensions());
                    assertEquals(3, response.getBody().vectorLength());
                    assertEquals("https://example.invalid/v1", response.getBody().baseUrl());
                    assertNull(response.getBody().error());
                })
                .verifyComplete();

        verify(tacticEmbeddingProbeService).probe(new TacticEmbeddingProbeService.ProbeRequest(
                "https://example.invalid/v1",
                "request-key",
                "probe-model",
                1536,
                2000));
    }
}
