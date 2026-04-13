package me.golemcore.bot.domain.context.layer;

import me.golemcore.bot.domain.component.MemoryComponent;
import me.golemcore.bot.domain.context.ContextLayerResult;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.MemoryPack;
import me.golemcore.bot.domain.model.MemoryQuery;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.service.MemoryPresetService;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MemoryLayerTest {

    private MemoryComponent memoryComponent;
    private RuntimeConfigService runtimeConfigService;
    private MemoryLayer layer;

    @BeforeEach
    void setUp() {
        memoryComponent = mock(MemoryComponent.class);
        runtimeConfigService = mock(RuntimeConfigService.class);
        layer = new MemoryLayer(memoryComponent, runtimeConfigService, new MemoryPresetService());
    }

    @Test
    void shouldAlwaysApply() {
        assertTrue(layer.appliesTo(AgentContext.builder().build()));
    }

    @Test
    void shouldSkipWhenMemoryPresetIsDisabled() {
        AgentContext context = AgentContext.builder()
                .attributes(Map.of(ContextAttributes.MEMORY_PRESET_ID, "disabled"))
                .build();

        assertFalse(layer.appliesTo(context));
        ContextLayerResult result = layer.assemble(context);

        assertFalse(result.hasContent());
        assertEquals("", context.getMemoryContext());
    }

    @ParameterizedTest
    @ValueSource(strings = { "telegram", "hive", "web" })
    void shouldKeepMemoryEnabledForNonWebhookChatsWhenPresetIsMissing(String channelType) {
        MemoryPack pack = MemoryPack.builder().renderedContext("").build();
        when(memoryComponent.buildMemoryPack(any())).thenReturn(pack);
        AgentContext context = AgentContext.builder()
                .messages(List.of(Message.builder().role("user").content("Hi").build()))
                .session(AgentSession.builder().channelType(channelType).chatId("1").build())
                .build();

        assertTrue(layer.appliesTo(context));
        layer.assemble(context);

        verify(memoryComponent).buildMemoryPack(any());
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
    void shouldApplyConfiguredMemoryPresetBudgets() {
        MemoryPack pack = MemoryPack.builder().renderedContext("").build();
        when(memoryComponent.buildMemoryPack(any())).thenReturn(pack);

        AgentContext context = AgentContext.builder()
                .messages(List.of(Message.builder().role("user").content("Hi").build()))
                .session(AgentSession.builder().channelType("web").chatId("1").build())
                .build();
        context.setAttribute(ContextAttributes.MEMORY_PRESET_ID, "general_chat");

        layer.assemble(context);

        ArgumentCaptor<MemoryQuery> queryCaptor = ArgumentCaptor.forClass(MemoryQuery.class);
        verify(memoryComponent).buildMemoryPack(queryCaptor.capture());
        MemoryQuery query = queryCaptor.getValue();
        assertEquals(1000, query.getSoftPromptBudgetTokens());
        assertEquals(1800, query.getMaxPromptBudgetTokens());
        assertEquals(4, query.getWorkingTopK());
        assertEquals(6, query.getEpisodicTopK());
        assertEquals(5, query.getSemanticTopK());
        assertEquals(1, query.getProceduralTopK());
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
