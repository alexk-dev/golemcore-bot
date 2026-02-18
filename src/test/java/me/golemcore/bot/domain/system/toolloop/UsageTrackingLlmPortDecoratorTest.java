package me.golemcore.bot.domain.system.toolloop;

import me.golemcore.bot.domain.model.LlmChunk;
import me.golemcore.bot.domain.model.LlmRequest;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.domain.model.LlmUsage;
import me.golemcore.bot.port.outbound.LlmPort;
import me.golemcore.bot.port.outbound.UsageTrackingPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UsageTrackingLlmPortDecoratorTest {

    private LlmPort delegate;
    private UsageTrackingPort usageTracker;
    private UsageTrackingLlmPortDecorator decorator;

    @BeforeEach
    void setUp() {
        delegate = mock(LlmPort.class);
        usageTracker = mock(UsageTrackingPort.class);
        when(delegate.getProviderId()).thenReturn("test-provider");
        decorator = new UsageTrackingLlmPortDecorator(delegate, usageTracker);
    }

    @Test
    void shouldRecordUsageAfterChat() throws Exception {
        LlmRequest request = LlmRequest.builder().model("gpt-5").sessionId("sess-1").build();
        LlmUsage usage = new LlmUsage();
        usage.setInputTokens(10);
        usage.setOutputTokens(20);
        LlmResponse response = LlmResponse.builder()
                .content("Hello")
                .model("gpt-5")
                .usage(usage)
                .build();
        when(delegate.chat(request)).thenReturn(CompletableFuture.completedFuture(response));

        LlmResponse result = decorator.chat(request).get();

        assertEquals("Hello", result.getContent());
        verify(usageTracker).recordUsage(eq("test-provider"), eq("gpt-5"), any(LlmUsage.class));
        assertNotNull(usage.getLatency());
        assertNotNull(usage.getTimestamp());
        assertEquals("sess-1", usage.getSessionId());
        assertEquals("gpt-5", usage.getModel());
        assertEquals("test-provider", usage.getProviderId());
    }

    @Test
    void shouldReturnResponseUnchanged() throws Exception {
        LlmRequest request = LlmRequest.builder().model("gpt-5").build();
        LlmUsage usage = new LlmUsage();
        LlmResponse response = LlmResponse.builder()
                .content("result")
                .model("gpt-5")
                .finishReason("stop")
                .usage(usage)
                .build();
        when(delegate.chat(request)).thenReturn(CompletableFuture.completedFuture(response));

        LlmResponse result = decorator.chat(request).get();

        assertEquals("result", result.getContent());
        assertEquals("stop", result.getFinishReason());
    }

    @Test
    void shouldSkipRecordingWhenResponseIsNull() throws Exception {
        LlmRequest request = LlmRequest.builder().build();
        when(delegate.chat(request)).thenReturn(CompletableFuture.completedFuture(null));

        LlmResponse result = decorator.chat(request).get();

        assertNull(result);
        verify(usageTracker, never()).recordUsage(any(), any(), any());
    }

    @Test
    void shouldSkipRecordingWhenUsageIsNull() throws Exception {
        LlmRequest request = LlmRequest.builder().build();
        LlmResponse response = LlmResponse.builder().content("ok").usage(null).build();
        when(delegate.chat(request)).thenReturn(CompletableFuture.completedFuture(response));

        LlmResponse result = decorator.chat(request).get();

        assertEquals("ok", result.getContent());
        verify(usageTracker, never()).recordUsage(any(), any(), any());
    }

    @Test
    void shouldFallbackToRequestModelWhenResponseModelIsNull() throws Exception {
        LlmRequest request = LlmRequest.builder().model("requested-model").build();
        LlmUsage usage = new LlmUsage();
        LlmResponse response = LlmResponse.builder().content("ok").model(null).usage(usage).build();
        when(delegate.chat(request)).thenReturn(CompletableFuture.completedFuture(response));

        decorator.chat(request).get();

        assertEquals("requested-model", usage.getModel());
    }

    @Test
    void shouldNotThrowWhenTrackerFails() throws Exception {
        LlmRequest request = LlmRequest.builder().model("gpt-5").build();
        LlmUsage usage = new LlmUsage();
        LlmResponse response = LlmResponse.builder().content("ok").model("gpt-5").usage(usage).build();
        when(delegate.chat(request)).thenReturn(CompletableFuture.completedFuture(response));
        doThrow(new RuntimeException("DB error")).when(usageTracker).recordUsage(any(), any(), any());

        LlmResponse result = decorator.chat(request).get();

        assertEquals("ok", result.getContent());
    }

    @Test
    void shouldNotTrackUsageForStream() {
        LlmRequest request = LlmRequest.builder().build();
        Flux<LlmChunk> flux = Flux.just(LlmChunk.builder().text("chunk").build());
        when(delegate.chatStream(request)).thenReturn(flux);

        Flux<LlmChunk> result = decorator.chatStream(request);

        assertNotNull(result);
        verify(delegate).chatStream(request);
        verify(usageTracker, never()).recordUsage(any(), any(), any());
    }

    @Test
    void shouldDelegateGetProviderId() {
        assertEquals("test-provider", decorator.getProviderId());
    }

    @Test
    void shouldDelegateSupportsStreaming() {
        when(delegate.supportsStreaming()).thenReturn(true);
        assertTrue(decorator.supportsStreaming());

        when(delegate.supportsStreaming()).thenReturn(false);
        assertFalse(decorator.supportsStreaming());
    }

    @Test
    void shouldDelegateGetSupportedModels() {
        List<String> models = List.of("gpt-5", "gpt-5.1");
        when(delegate.getSupportedModels()).thenReturn(models);

        assertEquals(models, decorator.getSupportedModels());
    }

    @Test
    void shouldDelegateGetCurrentModel() {
        when(delegate.getCurrentModel()).thenReturn("gpt-5");
        assertEquals("gpt-5", decorator.getCurrentModel());
    }

    @Test
    void shouldDelegateIsAvailable() {
        when(delegate.isAvailable()).thenReturn(true);
        assertTrue(decorator.isAvailable());
    }

    private void assertNull(Object obj) {
        org.junit.jupiter.api.Assertions.assertNull(obj);
    }
}
