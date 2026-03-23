package me.golemcore.bot.domain.context.layer;

import me.golemcore.bot.domain.component.MemoryComponent;
import me.golemcore.bot.domain.context.ContextLayerResult;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.MemoryPack;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MemoryLayerTest {

    private MemoryComponent memoryComponent;
    private RuntimeConfigService runtimeConfigService;
    private MemoryLayer layer;

    @BeforeEach
    void setUp() {
        memoryComponent = mock(MemoryComponent.class);
        runtimeConfigService = mock(RuntimeConfigService.class);
        layer = new MemoryLayer(memoryComponent, runtimeConfigService);
    }

    @Test
    void shouldAlwaysApply() {
        assertTrue(layer.appliesTo(AgentContext.builder().build()));
    }

    @Test
    void shouldRenderMemoryContextWithHeader() {
        MemoryPack pack = MemoryPack.builder()
                .renderedContext("User likes Java.\nUser is a senior engineer.")
                .diagnostics(Map.of("items", 2))
                .build();
        when(memoryComponent.buildMemoryPack(any())).thenReturn(pack);

        Message userMsg = Message.builder().role("user").content("Hello").build();
        AgentContext context = AgentContext.builder()
                .messages(List.of(userMsg))
                .session(AgentSession.builder().channelType("web").chatId("1").build())
                .build();

        ContextLayerResult result = layer.assemble(context);

        assertTrue(result.hasContent());
        assertTrue(result.getContent().contains("# Memory"));
        assertTrue(result.getContent().contains("User likes Java."));
    }

    @Test
    void shouldSetMemoryContextOnAgentContext() {
        MemoryPack pack = MemoryPack.builder().renderedContext("memory content").build();
        when(memoryComponent.buildMemoryPack(any())).thenReturn(pack);

        Message userMsg = Message.builder().role("user").content("Hi").build();
        AgentContext context = AgentContext.builder()
                .messages(List.of(userMsg))
                .session(AgentSession.builder().channelType("web").chatId("1").build())
                .build();

        layer.assemble(context);

        assertEquals("memory content", context.getMemoryContext());
    }

    @Test
    void shouldStoreDiagnosticsInAttributes() {
        MemoryPack pack = MemoryPack.builder()
                .renderedContext("content")
                .diagnostics(Map.of("topK", 5))
                .build();
        when(memoryComponent.buildMemoryPack(any())).thenReturn(pack);

        Message userMsg = Message.builder().role("user").content("Hi").build();
        AgentContext context = AgentContext.builder()
                .messages(List.of(userMsg))
                .session(AgentSession.builder().channelType("web").chatId("1").build())
                .build();

        layer.assemble(context);

        Map<String, Object> diagnostics = context.getAttribute(ContextAttributes.MEMORY_PACK_DIAGNOSTICS);
        assertEquals(5, diagnostics.get("topK"));
    }

    @Test
    void shouldReturnEmptyResultOnException() {
        when(memoryComponent.buildMemoryPack(any())).thenThrow(new RuntimeException("fail"));

        AgentContext context = AgentContext.builder()
                .session(AgentSession.builder().channelType("web").chatId("1").build())
                .build();

        ContextLayerResult result = layer.assemble(context);
        assertFalse(result.hasContent());
    }

    @Test
    void shouldHaveCorrectNameAndOrder() {
        assertEquals("memory", layer.getName());
        assertEquals(30, layer.getOrder());
    }
}
