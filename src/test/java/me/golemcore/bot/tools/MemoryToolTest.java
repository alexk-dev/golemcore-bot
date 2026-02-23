package me.golemcore.bot.tools;

import me.golemcore.bot.domain.component.MemoryComponent;
import me.golemcore.bot.domain.model.MemoryItem;
import me.golemcore.bot.domain.model.MemoryQuery;
import me.golemcore.bot.domain.model.ToolDefinition;
import me.golemcore.bot.domain.model.ToolResult;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatcher;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MemoryToolTest {

    private MemoryComponent memoryComponent;
    private RuntimeConfigService runtimeConfigService;
    private MemoryTool tool;

    @BeforeEach
    void setUp() {
        memoryComponent = mock(MemoryComponent.class);
        runtimeConfigService = mock(RuntimeConfigService.class);
        when(runtimeConfigService.isMemoryEnabled()).thenReturn(true);
        when(runtimeConfigService.getMemorySoftPromptBudgetTokens()).thenReturn(1800);
        when(runtimeConfigService.getMemoryMaxPromptBudgetTokens()).thenReturn(3500);
        when(runtimeConfigService.getMemoryPromotionMinConfidence()).thenReturn(0.75);
        tool = new MemoryTool(memoryComponent, runtimeConfigService);
    }

    @Test
    void shouldExposeDefinition() {
        ToolDefinition definition = tool.getDefinition();

        assertEquals("memory", definition.getName());
        assertNotNull(definition.getInputSchema());
        assertTrue(definition.getInputSchema().containsKey("required"));
    }

    @Test
    void shouldFailWhenMemoryDisabled() {
        when(runtimeConfigService.isMemoryEnabled()).thenReturn(false);

        ToolResult result = tool.execute(Map.of("operation", "memory_search")).join();

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("disabled"));
        verify(memoryComponent, never()).queryItems(any(MemoryQuery.class));
    }

    @Test
    void shouldFailWhenParametersMissing() {
        ToolResult result = tool.execute(null).join();

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("Parameters are required"));
    }

    @Test
    void shouldRejectUnknownOperation() {
        ToolResult result = tool.execute(Map.of("operation", "memory_unknown")).join();

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("Unknown operation"));
    }

    @Test
    void shouldAddSemanticMemoryItemWithClampedValuesAndDefaults() {
        ToolResult result = tool.execute(Map.of(
                "operation", "memory_add",
                "content", "  Project uses Spring Boot  ",
                "confidence", 5.0,
                "salience", -2.0,
                "ttl_days", -7,
                "tags", List.of("backend", " ", "spring")))
                .join();

        assertTrue(result.isSuccess());
        verify(memoryComponent).upsertSemanticItem(argThatItem(item -> item.getLayer() == MemoryItem.Layer.SEMANTIC
                && item.getType() == MemoryItem.Type.PROJECT_FACT
                && "Project uses Spring Boot".equals(item.getContent())
                && Double.valueOf(1.0).equals(item.getConfidence())
                && Double.valueOf(0.0).equals(item.getSalience())
                && Integer.valueOf(0).equals(item.getTtlDays())
                && item.getTags().size() == 2));
    }

    @Test
    void shouldRejectAddWithInvalidLayer() {
        ToolResult result = tool.execute(Map.of(
                "operation", "memory_add",
                "content", "x",
                "layer", "episodic"))
                .join();

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("layer must be"));
    }

    @Test
    void shouldRejectAddWithInvalidType() {
        ToolResult result = tool.execute(Map.of(
                "operation", "memory_add",
                "content", "x",
                "type", "unsupported_type"))
                .join();

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("Invalid memory type"));
    }

    @Test
    void shouldSearchMemoryItemsWithClampedLimit() {
        MemoryItem item = MemoryItem.builder()
                .id("m1")
                .layer(MemoryItem.Layer.SEMANTIC)
                .type(MemoryItem.Type.PROJECT_FACT)
                .title("Stack")
                .content("Uses Java and Spring")
                .build();
        when(memoryComponent.queryItems(any(MemoryQuery.class))).thenReturn(List.of(item));

        ToolResult result = tool.execute(Map.of(
                "operation", "memory_search",
                "query", "spring",
                "limit", 999))
                .join();

        assertTrue(result.isSuccess());
        assertTrue(result.getOutput().contains("spring") || result.getOutput().contains("Spring"));
        verify(memoryComponent).queryItems(argThatQuery(query -> "spring".equals(query.getQueryText())
                && Integer.valueOf(50).equals(query.getSemanticTopK())
                && Integer.valueOf(50).equals(query.getProceduralTopK())));
    }

    @Test
    void shouldReturnNoResultsWhenSearchEmpty() {
        when(memoryComponent.queryItems(any(MemoryQuery.class))).thenReturn(List.of());

        ToolResult result = tool.execute(Map.of("operation", "memory_search")).join();

        assertTrue(result.isSuccess());
        assertEquals("No memory items found.", result.getOutput());
    }

    @Test
    void shouldRequireIdentityForUpdate() {
        ToolResult result = tool.execute(Map.of(
                "operation", "memory_update",
                "content", "Updated content"))
                .join();

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("requires id or fingerprint"));
        verify(memoryComponent, never()).upsertSemanticItem(any(MemoryItem.class));
    }

    @Test
    void shouldRejectUpdateWithoutPayload() {
        ToolResult result = tool.execute(Map.of(
                "operation", "memory_update",
                "id", "mem-1",
                "layer", "semantic"))
                .join();

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("at least one field to update"));
    }

    @Test
    void shouldUpdateProceduralItem() {
        ToolResult result = tool.execute(Map.of(
                "operation", "memory_update",
                "fingerprint", "fp-1",
                "layer", "procedural",
                "title", "Refined title",
                "content", "Updated procedure",
                "references", List.of("Runbook.md")))
                .join();

        assertTrue(result.isSuccess());
        verify(memoryComponent).upsertProceduralItem(argThatItem(item -> "fp-1".equals(item.getFingerprint())
                && "Updated procedure".equals(item.getContent())
                && item.getLayer() == MemoryItem.Layer.PROCEDURAL
                && item.getReferences().contains("Runbook.md")));
    }

    @Test
    void shouldPromoteToProceduralLayerFromSearchResult() {
        MemoryItem item = MemoryItem.builder()
                .id("m2")
                .layer(MemoryItem.Layer.EPISODIC)
                .type(MemoryItem.Type.FIX)
                .title("Fix")
                .content("Fixed failing test")
                .confidence(0.9)
                .build();
        when(memoryComponent.queryItems(any(MemoryQuery.class))).thenReturn(List.of(item));

        ToolResult result = tool.execute(Map.of(
                "operation", "memory_promote",
                "query", "failing test",
                "layer", "procedural"))
                .join();

        assertTrue(result.isSuccess());
        verify(memoryComponent).upsertProceduralItem(any(MemoryItem.class));
    }

    @Test
    void shouldPromoteExplicitContentAndRaiseConfidenceToMinimum() {
        when(runtimeConfigService.getMemoryPromotionMinConfidence()).thenReturn(0.82);

        ToolResult result = tool.execute(Map.of(
                "operation", "memory_promote",
                "layer", "semantic",
                "content", "We decided to use Redis",
                "confidence", 0.40))
                .join();

        assertTrue(result.isSuccess());
        verify(memoryComponent).upsertSemanticItem(argThatItem(item -> item.getLayer() == MemoryItem.Layer.SEMANTIC
                && item.getType() == MemoryItem.Type.DECISION
                && item.getConfidence() >= 0.82));
    }

    @Test
    void shouldFailPromoteWhenNoSourceFound() {
        when(memoryComponent.queryItems(any(MemoryQuery.class))).thenReturn(List.of());

        ToolResult result = tool.execute(Map.of(
                "operation", "memory_promote",
                "query", "missing"))
                .join();

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("No source memory item found"));
    }

    @Test
    void shouldForgetByIdUsingTombstone() {
        when(memoryComponent.queryItems(any(MemoryQuery.class))).thenReturn(List.of());

        ToolResult result = tool.execute(Map.of(
                "operation", "memory_forget",
                "id", "abc123"))
                .join();

        assertTrue(result.isSuccess());
        verify(memoryComponent).upsertSemanticItem(argThatItem(item -> "abc123".equals(item.getId())
                && Integer.valueOf(0).equals(item.getTtlDays())));
    }

    @Test
    void shouldForgetMatchedProceduralItemByQuery() {
        MemoryItem item = MemoryItem.builder()
                .id("proc-1")
                .layer(MemoryItem.Layer.PROCEDURAL)
                .type(MemoryItem.Type.FIX)
                .title("Fix")
                .content("fix steps")
                .build();
        when(memoryComponent.queryItems(any(MemoryQuery.class))).thenReturn(List.of(item));

        ToolResult result = tool.execute(Map.of(
                "operation", "memory_forget",
                "query", "fix steps"))
                .join();

        assertTrue(result.isSuccess());
        verify(memoryComponent).upsertProceduralItem(argThatItem(memoryItem -> "proc-1".equals(memoryItem.getId())
                && Integer.valueOf(0).equals(memoryItem.getTtlDays())));
    }

    @Test
    void shouldFailForgetWhenNoMatchAndNoIdentity() {
        when(memoryComponent.queryItems(any(MemoryQuery.class))).thenReturn(List.of());

        ToolResult result = tool.execute(Map.of(
                "operation", "memory_forget",
                "query", "missing"))
                .join();

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("No memory items matched"));
    }

    @Test
    void shouldRejectInvalidLayerOnForget() {
        ToolResult result = tool.execute(Map.of(
                "operation", "memory_forget",
                "layer", "working",
                "id", "x"))
                .join();

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("layer must be"));
        verify(memoryComponent, never()).upsertSemanticItem(any(MemoryItem.class));
    }

    private static MemoryQuery argThatQuery(ArgumentMatcher<MemoryQuery> matcher) {
        return org.mockito.ArgumentMatchers.argThat(matcher);
    }

    private static MemoryItem argThatItem(ArgumentMatcher<MemoryItem> matcher) {
        return org.mockito.ArgumentMatchers.argThat(matcher);
    }
}
