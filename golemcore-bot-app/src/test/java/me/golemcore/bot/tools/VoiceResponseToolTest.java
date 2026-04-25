package me.golemcore.bot.tools;

import me.golemcore.bot.domain.loop.AgentContextHolder;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.ToolDefinition;
import me.golemcore.bot.domain.model.ToolResult;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VoiceResponseToolTest {

    private static final String TEXT = "text";

    private VoiceResponseTool tool;
    private RuntimeConfigService runtimeConfigService;

    @BeforeEach
    void setUp() {
        runtimeConfigService = mock(RuntimeConfigService.class);
        when(runtimeConfigService.isVoiceEnabled()).thenReturn(true);
        tool = new VoiceResponseTool(runtimeConfigService);
    }

    @AfterEach
    void tearDown() {
        AgentContextHolder.clear();
    }

    private AgentContext createContext() {
        AgentSession session = AgentSession.builder()
                .id("test-session")
                .chatId("chat1")
                .channelType("telegram")
                .messages(new ArrayList<>())
                .build();
        return AgentContext.builder()
                .session(session)
                .messages(new ArrayList<>())
                .build();
    }

    @Test
    void toolNameIsSendVoice() {
        assertEquals("send_voice", tool.getToolName());
    }

    @SuppressWarnings("unchecked")
    @Test
    void definitionHasCorrectName() {
        ToolDefinition def = tool.getDefinition();
        assertEquals("send_voice", def.getName());
        assertNotNull(def.getDescription());
        assertNotNull(def.getInputSchema());
        // text is required
        List<String> required = (List<String>) def.getInputSchema().get("required");
        assertTrue(required.contains(TEXT));
    }

    @Test
    void executeSetsVoiceRequestedAttribute() throws Exception {
        AgentContext context = createContext();
        AgentContextHolder.set(context);

        CompletableFuture<ToolResult> future = tool.execute(Map.of(TEXT, "Hello world"));
        ToolResult result = future.get();

        assertTrue(result.isSuccess());
        assertEquals("Voice response queued", result.getOutput());
        assertTrue(context.isVoiceRequested());
        assertEquals("Hello world", context.getVoiceText());
        assertTrue(Boolean.TRUE.equals(context.getAttribute(ContextAttributes.FINAL_ANSWER_READY)));
    }

    @Test
    void executeWithoutTextDoesNotSetVoiceText() throws Exception {
        AgentContext context = createContext();
        AgentContextHolder.set(context);

        CompletableFuture<ToolResult> future = tool.execute(Map.of());
        ToolResult result = future.get();

        assertTrue(result.isSuccess());
        assertTrue(context.isVoiceRequested());
        assertNull(context.getVoiceText());
    }

    @Test
    void executeWithBlankTextDoesNotSetVoiceText() throws Exception {
        AgentContext context = createContext();
        AgentContextHolder.set(context);

        CompletableFuture<ToolResult> future = tool.execute(Map.of(TEXT, "   "));
        ToolResult result = future.get();

        assertTrue(result.isSuccess());
        assertNull(context.getVoiceText());
    }

    @Test
    void executeFailsWithoutContext() throws Exception {
        AgentContextHolder.clear();

        CompletableFuture<ToolResult> future = tool.execute(Map.of(TEXT, "Hello"));
        ToolResult result = future.get();

        assertFalse(result.isSuccess());
        assertEquals("No agent context available", result.getError());
    }

    @Test
    void isEnabledWhenVoiceEnabled() {
        when(runtimeConfigService.isVoiceEnabled()).thenReturn(true);
        assertTrue(tool.isEnabled());
    }

    @Test
    void isDisabledWhenVoiceDisabled() {
        when(runtimeConfigService.isVoiceEnabled()).thenReturn(false);
        assertFalse(tool.isEnabled());
    }

    // ===== Edge cases =====

    @Test
    void executeWithNumericTextConvertsToString() throws Exception {
        AgentContext context = createContext();
        AgentContextHolder.set(context);

        // text parameter as Integer — String.valueOf should handle it
        CompletableFuture<ToolResult> future = tool.execute(Map.of(TEXT, 42));
        ToolResult result = future.get();

        assertTrue(result.isSuccess());
        assertEquals("42", context.getVoiceText());
    }

    @Test
    void executeWithVeryLongTextStillQueues() throws Exception {
        AgentContext context = createContext();
        AgentContextHolder.set(context);

        String longText = "A".repeat(50_000);
        CompletableFuture<ToolResult> future = tool.execute(Map.of(TEXT, longText));
        ToolResult result = future.get();

        assertTrue(result.isSuccess());
        assertEquals(longText, context.getVoiceText());
    }

    @Test
    void definitionHasDescription() {
        ToolDefinition def = tool.getDefinition();
        assertNotNull(def.getDescription());
        assertFalse(def.getDescription().isBlank());
    }

    @Test
    void executeSetsLoopComplete() throws Exception {
        AgentContext context = createContext();
        AgentContextHolder.set(context);

        tool.execute(Map.of(TEXT, "test")).get();
        assertTrue(Boolean.TRUE.equals(context.getAttribute(ContextAttributes.FINAL_ANSWER_READY)));
    }
}
