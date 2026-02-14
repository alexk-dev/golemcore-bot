package me.golemcore.bot.adapter.outbound.llm;

import me.golemcore.bot.domain.model.LlmChunk;
import me.golemcore.bot.domain.model.LlmRequest;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.port.outbound.LlmPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NoOpLlmAdapterTest {

    private NoOpLlmAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new NoOpLlmAdapter();
    }

    // --- chat ---

    @Test
    void shouldReturnPlaceholderResponseFromChat() throws Exception {
        LlmRequest request = LlmRequest.builder()
                .model("test-model")
                .build();

        CompletableFuture<LlmResponse> future = adapter.chat(request);
        LlmResponse response = future.get();

        assertEquals("[No LLM configured]", response.getContent());
        assertEquals("none", response.getModel());
        assertEquals("stop", response.getFinishReason());
    }

    @Test
    void shouldReturnZeroUsageFromChat() throws Exception {
        LlmRequest request = LlmRequest.builder().build();

        LlmResponse response = adapter.chat(request).get();

        assertNotNull(response.getUsage());
        assertEquals(0, response.getUsage().getInputTokens());
        assertEquals(0, response.getUsage().getOutputTokens());
        assertEquals(0, response.getUsage().getTotalTokens());
    }

    @Test
    void shouldReturnCompletedFutureFromChat() {
        LlmRequest request = LlmRequest.builder().build();

        CompletableFuture<LlmResponse> future = adapter.chat(request);

        assertTrue(future.isDone());
        assertFalse(future.isCompletedExceptionally());
    }

    @Test
    void shouldReturnCompleteResponseFromChat() throws Exception {
        LlmRequest request = LlmRequest.builder().build();

        LlmResponse response = adapter.chat(request).get();

        assertTrue(response.isComplete());
    }

    @Test
    void shouldReturnNoToolCallsFromChat() throws Exception {
        LlmRequest request = LlmRequest.builder().build();

        LlmResponse response = adapter.chat(request).get();

        assertFalse(response.hasToolCalls());
    }

    // --- chatStream ---

    @Test
    void shouldReturnPlaceholderChunkFromChatStream() {
        LlmRequest request = LlmRequest.builder().build();

        Flux<LlmChunk> stream = adapter.chatStream(request);

        StepVerifier.create(stream)
                .assertNext(chunk -> {
                    assertEquals("[No LLM configured]", chunk.getText());
                    assertTrue(chunk.isDone());
                })
                .verifyComplete();
    }

    @Test
    void shouldEmitExactlyOneChunkFromChatStream() {
        LlmRequest request = LlmRequest.builder().build();

        Flux<LlmChunk> stream = adapter.chatStream(request);

        StepVerifier.create(stream)
                .expectNextCount(1)
                .verifyComplete();
    }

    // --- supportsStreaming ---

    @Test
    void shouldReturnFalseForSupportsStreaming() {
        assertFalse(adapter.supportsStreaming());
    }

    // --- getSupportedModels ---

    @Test
    void shouldReturnEmptyListForSupportedModels() {
        List<String> models = adapter.getSupportedModels();

        assertNotNull(models);
        assertTrue(models.isEmpty());
    }

    // --- getCurrentModel ---

    @Test
    void shouldReturnNoneForCurrentModel() {
        assertEquals("none", adapter.getCurrentModel());
    }

    // --- isAvailable ---

    @Test
    void shouldReturnFalseForIsAvailable() {
        assertFalse(adapter.isAvailable());
    }

    // --- getProviderId ---

    @Test
    void shouldReturnNoneForProviderId() {
        assertEquals("none", adapter.getProviderId());
    }

    // --- getLlmPort ---

    @Test
    void shouldReturnSelfForGetLlmPort() {
        LlmPort port = adapter.getLlmPort();

        assertSame(adapter, port);
    }

    // --- initialize ---

    @Test
    void shouldNotThrowWhenInitializeCalled() {
        adapter.initialize();
        // No exception means success - initialize is a no-op
    }

    // --- getComponentType (from LlmComponent) ---

    @Test
    void shouldReturnLlmForComponentType() {
        assertEquals("llm", adapter.getComponentType());
    }
}
