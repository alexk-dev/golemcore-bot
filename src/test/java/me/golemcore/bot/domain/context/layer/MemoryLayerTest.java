package me.golemcore.bot.domain.context.layer;

import me.golemcore.bot.domain.component.MemoryComponent;
import me.golemcore.bot.domain.context.ContextLayerResult;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.MemoryPack;
import me.golemcore.bot.domain.model.MemoryQuery;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.MemoryPreset;
import me.golemcore.bot.domain.model.RuntimeConfig;
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

    // --- Additional mutation-hardening coverage -------------------------------

    @Test
    void shouldPassUserMessageTextAsQueryWhenAssembling() {
        // Guards against getLastUserMessageText returning "" or null instead of
        // the actual user message content (EmptyObjectReturnValsMutator).
        MemoryPack pack = MemoryPack.builder().renderedContext("").build();
        when(memoryComponent.buildMemoryPack(any())).thenReturn(pack);

        Message userMessage = Message.builder().role("user").content("Remember my cat's name").build();
        AgentContext context = AgentContext.builder()
                .messages(List.of(userMessage))
                .session(AgentSession.builder().channelType("web").chatId("1").build())
                .build();

        layer.assemble(context);

        ArgumentCaptor<MemoryQuery> captor = ArgumentCaptor.forClass(MemoryQuery.class);
        verify(memoryComponent).buildMemoryPack(captor.capture());
        assertEquals("Remember my cat's name", captor.getValue().getQueryText());
    }

    @Test
    void shouldSkipInternalAndNonUserMessagesWhenResolvingQueryText() {
        // Walks the message list backwards past an internal user message and an
        // assistant message to find the last real user turn — prevents the
        // getLastUserMessageText branch from collapsing to an empty string.
        MemoryPack pack = MemoryPack.builder().renderedContext("").build();
        when(memoryComponent.buildMemoryPack(any())).thenReturn(pack);

        Message firstUser = Message.builder().role("user").content("real user question").build();
        Message assistant = Message.builder().role("assistant").content("assistant reply").build();
        Message internalUser = Message.builder()
                .role("user")
                .content("[INTERNAL] trace")
                .metadata(new java.util.HashMap<>(Map.of(ContextAttributes.MESSAGE_INTERNAL, Boolean.TRUE)))
                .build();
        AgentContext context = AgentContext.builder()
                .messages(List.of(firstUser, assistant, internalUser))
                .session(AgentSession.builder().channelType("web").chatId("1").build())
                .build();

        layer.assemble(context);

        ArgumentCaptor<MemoryQuery> captor = ArgumentCaptor.forClass(MemoryQuery.class);
        verify(memoryComponent).buildMemoryPack(captor.capture());
        assertEquals("real user question", captor.getValue().getQueryText());
    }

    @Test
    void shouldKeepMemoryEnabledForNonDisabledPreset() {
        // Guards isMemoryDisabled against NegateConditionalsMutator on the
        // MemoryPresetIds.DISABLED equality check: any other preset must NOT
        // disable memory.
        AgentContext context = AgentContext.builder()
                .attributes(Map.of(ContextAttributes.MEMORY_PRESET_ID, "general_chat"))
                .build();

        assertTrue(layer.appliesTo(context));
    }

    @Test
    void shouldUseRuntimeConfigFallbacksWhenNoPresetConfigured() {
        // When no preset is resolved, resolveSoftPromptBudget / resolveMaxPromptBudget
        // / resolveWorkingTopK / resolveEpisodicTopK / resolveSemanticTopK /
        // resolveProceduralTopK all fall through to the RuntimeConfigService.
        // Stubbing them with distinctive non-zero values and asserting each
        // value on the captured MemoryQuery kills PrimitiveReturnsMutator which
        // would otherwise replace each fallback return with 0.
        when(runtimeConfigService.getMemorySoftPromptBudgetTokens()).thenReturn(711);
        when(runtimeConfigService.getMemoryMaxPromptBudgetTokens()).thenReturn(1492);
        when(runtimeConfigService.getMemoryWorkingTopK()).thenReturn(3);
        when(runtimeConfigService.getMemoryEpisodicTopK()).thenReturn(9);
        when(runtimeConfigService.getMemorySemanticTopK()).thenReturn(7);
        when(runtimeConfigService.getMemoryProceduralTopK()).thenReturn(2);

        MemoryPack pack = MemoryPack.builder().renderedContext("").build();
        when(memoryComponent.buildMemoryPack(any())).thenReturn(pack);

        AgentContext context = AgentContext.builder()
                .messages(List.of(Message.builder().role("user").content("Hi").build()))
                .session(AgentSession.builder().channelType("web").chatId("1").build())
                .build();

        layer.assemble(context);

        ArgumentCaptor<MemoryQuery> captor = ArgumentCaptor.forClass(MemoryQuery.class);
        verify(memoryComponent).buildMemoryPack(captor.capture());
        MemoryQuery query = captor.getValue();
        assertEquals(711, query.getSoftPromptBudgetTokens());
        assertEquals(1492, query.getMaxPromptBudgetTokens());
        assertEquals(3, query.getWorkingTopK());
        assertEquals(9, query.getEpisodicTopK());
        assertEquals(7, query.getSemanticTopK());
        assertEquals(2, query.getProceduralTopK());
    }

    @Test
    void shouldUsePresetOverridesWhenPartiallyConfigured() {
        // Each resolve* helper has a short-circuit path where the preset value
        // takes precedence over the runtime fallback. By setting only a subset
        // of preset fields we exercise both branches simultaneously and assert
        // the correct source is used for each knob.
        MemoryPresetService presetService = mock(MemoryPresetService.class);
        RuntimeConfig.MemoryConfig partial = new RuntimeConfig.MemoryConfig();
        partial.setSoftPromptBudgetTokens(123);
        partial.setMaxPromptBudgetTokens(456);
        partial.setWorkingTopK(11);
        // Null out the remaining top-K fields (the MemoryConfig default
        // constructor pre-populates them) so the fallback branch in
        // MemoryLayer::resolve{Episodic,Semantic,Procedural}TopK is exercised.
        partial.setEpisodicTopK(null);
        partial.setSemanticTopK(null);
        partial.setProceduralTopK(null);
        partial.setEnabled(Boolean.TRUE);
        MemoryPreset preset = MemoryPreset.builder().id("partial").memory(partial).build();
        when(presetService.findById("partial")).thenReturn(java.util.Optional.of(preset));

        MemoryLayer partialLayer = new MemoryLayer(memoryComponent, runtimeConfigService, presetService);

        when(runtimeConfigService.getMemoryEpisodicTopK()).thenReturn(42);
        when(runtimeConfigService.getMemorySemanticTopK()).thenReturn(77);
        when(runtimeConfigService.getMemoryProceduralTopK()).thenReturn(99);

        MemoryPack pack = MemoryPack.builder().renderedContext("").build();
        when(memoryComponent.buildMemoryPack(any())).thenReturn(pack);

        AgentContext context = AgentContext.builder()
                .messages(List.of(Message.builder().role("user").content("Hi").build()))
                .session(AgentSession.builder().channelType("web").chatId("1").build())
                .attributes(Map.of(ContextAttributes.MEMORY_PRESET_ID, "partial"))
                .build();

        partialLayer.assemble(context);

        ArgumentCaptor<MemoryQuery> captor = ArgumentCaptor.forClass(MemoryQuery.class);
        verify(memoryComponent).buildMemoryPack(captor.capture());
        MemoryQuery query = captor.getValue();
        assertEquals(123, query.getSoftPromptBudgetTokens());
        assertEquals(456, query.getMaxPromptBudgetTokens());
        assertEquals(11, query.getWorkingTopK());
        assertEquals(42, query.getEpisodicTopK());
        assertEquals(77, query.getSemanticTopK());
        assertEquals(99, query.getProceduralTopK());
    }

    @Test
    void shouldReportNonZeroEstimatedTokensForRenderedContent() {
        // The rendered "# Memory\n<content>" string is non-empty, so
        // TokenEstimator must return a positive integer — guards against
        // PrimitiveReturnsMutator on the estimatedTokens field.
        MemoryPack pack = MemoryPack.builder()
                .renderedContext("user lives in Munich")
                .build();
        when(memoryComponent.buildMemoryPack(any())).thenReturn(pack);

        AgentContext context = AgentContext.builder()
                .messages(List.of(Message.builder().role("user").content("Hi").build()))
                .session(AgentSession.builder().channelType("web").chatId("1").build())
                .build();

        ContextLayerResult result = layer.assemble(context);

        assertTrue(result.getEstimatedTokens() > 0);
        // Exact expected tokens: "# Memory\nuser lives in Munich" is 29 chars ->
        // ceil(29/3.5) = 9
        assertEquals(9, result.getEstimatedTokens());
    }
}
