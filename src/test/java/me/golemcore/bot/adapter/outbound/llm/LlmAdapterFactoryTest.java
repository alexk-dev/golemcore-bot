package me.golemcore.bot.adapter.outbound.llm;

import me.golemcore.bot.domain.component.LlmComponent;
import me.golemcore.bot.domain.model.LlmChunk;
import me.golemcore.bot.domain.model.LlmRequest;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.port.outbound.LlmPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LlmAdapterFactoryTest {

    private BotProperties properties;

    @BeforeEach
    void setUp() {
        properties = new BotProperties();
    }

    // ===== init() =====

    @Test
    void shouldSelectConfiguredProvider() {
        properties.getLlm().setProvider("langchain4j");
        LlmProviderAdapter langchain4j = createMockAdapter("langchain4j", true);
        LlmProviderAdapter noop = createMockAdapter("none", false);

        LlmAdapterFactory factory = new LlmAdapterFactory(properties, List.of(langchain4j, noop));
        factory.init();

        assertEquals("langchain4j", factory.getProviderId());
        assertSame(langchain4j, factory.getActiveAdapter());
    }

    @Test
    void shouldFallbackToNoopWhenProviderNotFound() {
        properties.getLlm().setProvider("nonexistent");
        LlmProviderAdapter noop = createMockAdapter("none", false);

        LlmAdapterFactory factory = new LlmAdapterFactory(properties, List.of(noop));
        factory.init();

        assertEquals("none", factory.getProviderId());
    }

    @Test
    void shouldFallbackToFirstAdapterWhenNoopNotFound() {
        properties.getLlm().setProvider("nonexistent");
        LlmProviderAdapter custom = createMockAdapter("custom", true);

        LlmAdapterFactory factory = new LlmAdapterFactory(properties, List.of(custom));
        factory.init();

        assertEquals("custom", factory.getProviderId());
    }

    @Test
    void shouldReturnNoneWhenNoAdapters() {
        properties.getLlm().setProvider("nonexistent");

        LlmAdapterFactory factory = new LlmAdapterFactory(properties, List.of());
        factory.init();

        assertEquals("none", factory.getProviderId());
    }

    // ===== getAdapter() =====

    @Test
    void shouldReturnAdapterByProviderId() {
        properties.getLlm().setProvider("langchain4j");
        LlmProviderAdapter langchain4j = createMockAdapter("langchain4j", true);
        LlmProviderAdapter custom = createMockAdapter("custom", true);

        LlmAdapterFactory factory = new LlmAdapterFactory(properties, List.of(langchain4j, custom));
        factory.init();

        assertSame(langchain4j, factory.getAdapter("langchain4j"));
        assertSame(custom, factory.getAdapter("custom"));
        assertNull(factory.getAdapter("nonexistent"));
    }

    // ===== isProviderAvailable() =====

    @Test
    void shouldCheckProviderAvailability() {
        properties.getLlm().setProvider("langchain4j");
        LlmProviderAdapter langchain4j = createMockAdapter("langchain4j", true);
        LlmProviderAdapter custom = createMockAdapter("custom", false);

        LlmAdapterFactory factory = new LlmAdapterFactory(properties, List.of(langchain4j, custom));
        factory.init();

        assertTrue(factory.isProviderAvailable("langchain4j"));
        assertFalse(factory.isProviderAvailable("custom"));
        assertFalse(factory.isProviderAvailable("nonexistent"));
    }

    // ===== getAllAdapters() =====

    @Test
    void shouldReturnAllAdapters() {
        properties.getLlm().setProvider("langchain4j");
        LlmProviderAdapter langchain4j = createMockAdapter("langchain4j", true);
        LlmProviderAdapter custom = createMockAdapter("custom", true);

        LlmAdapterFactory factory = new LlmAdapterFactory(properties, List.of(langchain4j, custom));
        factory.init();

        Map<String, LlmProviderAdapter> all = factory.getAllAdapters();
        assertEquals(2, all.size());
        assertTrue(all.containsKey("langchain4j"));
        assertTrue(all.containsKey("custom"));
    }

    // ===== getActiveLlmComponent() =====

    @Test
    void shouldReturnLlmComponentWhenActiveAdapterIsLlmComponent() {
        properties.getLlm().setProvider("none");
        NoOpLlmAdapter noop = new NoOpLlmAdapter();

        LlmAdapterFactory factory = new LlmAdapterFactory(properties, List.of(noop));
        factory.init();

        LlmComponent component = factory.getActiveLlmComponent();
        assertNotNull(component);
        assertSame(noop, component);
    }

    @Test
    void shouldReturnNullWhenActiveAdapterNotLlmComponent() {
        properties.getLlm().setProvider("mock");
        LlmProviderAdapter mockAdapter = createMockAdapter("mock", true);

        LlmAdapterFactory factory = new LlmAdapterFactory(properties, List.of(mockAdapter));
        factory.init();

        LlmComponent component = factory.getActiveLlmComponent();
        assertNull(component);
    }

    // ===== LlmPort delegation =====

    @Test
    void shouldDelegateChatToActiveAdapter() {
        properties.getLlm().setProvider("test");
        LlmProviderAdapter adapter = createMockAdapter("test", true);
        LlmRequest request = LlmRequest.builder().build();
        LlmResponse expectedResponse = LlmResponse.builder().content("hello").build();
        when(adapter.chat(request)).thenReturn(CompletableFuture.completedFuture(expectedResponse));

        LlmAdapterFactory factory = new LlmAdapterFactory(properties, List.of(adapter));
        factory.init();

        CompletableFuture<LlmResponse> result = factory.chat(request);
        assertEquals("hello", result.join().getContent());
    }

    @Test
    void shouldDelegateSupportsStreamingToActiveAdapter() {
        properties.getLlm().setProvider("test");
        LlmProviderAdapter adapter = createMockAdapter("test", true);
        when(adapter.supportsStreaming()).thenReturn(true);

        LlmAdapterFactory factory = new LlmAdapterFactory(properties, List.of(adapter));
        factory.init();

        assertTrue(factory.supportsStreaming());
    }

    @Test
    void shouldReturnFalseForStreamingWhenNoActiveAdapter() {
        properties.getLlm().setProvider("nonexistent");

        LlmAdapterFactory factory = new LlmAdapterFactory(properties, List.of());
        factory.init();

        assertFalse(factory.supportsStreaming());
    }

    @Test
    void shouldDelegateGetSupportedModels() {
        properties.getLlm().setProvider("test");
        LlmProviderAdapter adapter = createMockAdapter("test", true);
        when(adapter.getSupportedModels()).thenReturn(List.of("model-a", "model-b"));

        LlmAdapterFactory factory = new LlmAdapterFactory(properties, List.of(adapter));
        factory.init();

        assertEquals(List.of("model-a", "model-b"), factory.getSupportedModels());
    }

    @Test
    void shouldReturnEmptyModelsWhenNoActiveAdapter() {
        properties.getLlm().setProvider("nonexistent");

        LlmAdapterFactory factory = new LlmAdapterFactory(properties, List.of());
        factory.init();

        assertTrue(factory.getSupportedModels().isEmpty());
    }

    @Test
    void shouldDelegateGetCurrentModel() {
        properties.getLlm().setProvider("test");
        LlmProviderAdapter adapter = createMockAdapter("test", true);
        when(adapter.getCurrentModel()).thenReturn("gpt-4o");

        LlmAdapterFactory factory = new LlmAdapterFactory(properties, List.of(adapter));
        factory.init();

        assertEquals("gpt-4o", factory.getCurrentModel());
    }

    @Test
    void shouldReturnNoneModelWhenNoActiveAdapter() {
        properties.getLlm().setProvider("nonexistent");

        LlmAdapterFactory factory = new LlmAdapterFactory(properties, List.of());
        factory.init();

        assertEquals("none", factory.getCurrentModel());
    }

    @Test
    void shouldDelegateIsAvailable() {
        properties.getLlm().setProvider("test");
        LlmProviderAdapter adapter = createMockAdapter("test", true);

        LlmAdapterFactory factory = new LlmAdapterFactory(properties, List.of(adapter));
        factory.init();

        assertTrue(factory.isAvailable());
    }

    @Test
    void shouldReturnNotAvailableWhenNoActiveAdapter() {
        properties.getLlm().setProvider("nonexistent");

        LlmAdapterFactory factory = new LlmAdapterFactory(properties, List.of());
        factory.init();

        assertFalse(factory.isAvailable());
    }

    @Test
    void shouldDelegateChatStream() {
        properties.getLlm().setProvider("test");
        LlmProviderAdapter adapter = createMockAdapter("test", true);
        LlmRequest request = LlmRequest.builder().build();
        Flux<LlmChunk> expectedFlux = Flux.just(LlmChunk.builder().text("hi").done(true).build());
        when(adapter.chatStream(request)).thenReturn(expectedFlux);

        LlmAdapterFactory factory = new LlmAdapterFactory(properties, List.of(adapter));
        factory.init();

        Flux<LlmChunk> result = factory.chatStream(request);
        assertNotNull(result);
    }

    // ===== Helper =====

    private LlmProviderAdapter createMockAdapter(String providerId, boolean available) {
        LlmProviderAdapter adapter = mock(LlmProviderAdapter.class);
        when(adapter.getProviderId()).thenReturn(providerId);
        when(adapter.isAvailable()).thenReturn(available);
        return adapter;
    }
}
