package me.golemcore.bot.tools;

import me.golemcore.bot.domain.component.MemoryComponent;
import me.golemcore.bot.domain.model.MemoryItem;
import me.golemcore.bot.domain.model.MemoryQuery;
import me.golemcore.bot.domain.model.ToolResult;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatcher;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void shouldAddSemanticMemoryItem() {
        ToolResult result = tool.execute(Map.of(
                "operation", "memory_add",
                "content", "Project uses Spring Boot",
                "type", "project_fact"))
                .join();

        assertTrue(result.isSuccess());
        verify(memoryComponent).upsertSemanticItem(any(MemoryItem.class));
    }

    @Test
    void shouldSearchMemoryItems() {
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
                "limit", 3))
                .join();

        assertTrue(result.isSuccess());
        assertTrue(result.getOutput().contains("spring") || result.getOutput().contains("Spring"));
        verify(memoryComponent).queryItems(argThatQuery(query -> "spring".equals(query.getQueryText())
                && Integer.valueOf(3).equals(query.getSemanticTopK())));
    }

    @Test
    void shouldRequireIdentityForUpdate() {
        ToolResult result = tool.execute(Map.of(
                "operation", "memory_update",
                "content", "Updated content"))
                .join();

        assertEquals(false, result.isSuccess());
        assertTrue(result.getError().contains("requires id or fingerprint"));
        verify(memoryComponent, never()).upsertSemanticItem(any(MemoryItem.class));
    }

    @Test
    void shouldPromoteToProceduralLayer() {
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
    void shouldForgetByIdUsingTombstone() {
        ToolResult result = tool.execute(Map.of(
                "operation", "memory_forget",
                "id", "abc123"))
                .join();

        assertTrue(result.isSuccess());
        verify(memoryComponent).upsertSemanticItem(argThatItem(item -> "abc123".equals(item.getId())
                && Integer.valueOf(0).equals(item.getTtlDays())));
    }

    private static MemoryQuery argThatQuery(ArgumentMatcher<MemoryQuery> matcher) {
        return org.mockito.ArgumentMatchers.argThat(matcher);
    }

    private static MemoryItem argThatItem(ArgumentMatcher<MemoryItem> matcher) {
        return org.mockito.ArgumentMatchers.argThat(matcher);
    }
}
