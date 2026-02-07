package me.golemcore.bot.domain.system;

import me.golemcore.bot.domain.component.ToolComponent;
import me.golemcore.bot.domain.model.*;
import me.golemcore.bot.domain.service.ToolConfirmationPolicy;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.port.inbound.ChannelPort;
import me.golemcore.bot.port.outbound.ConfirmationPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ToolExecutionSystemTest {

    private ToolExecutionSystem system;
    private ToolComponent shellTool;
    private ToolComponent dateTimeTool;
    private ToolConfirmationPolicy confirmationPolicy;
    private ConfirmationPort confirmationPort;
    private ChannelPort channelPort;
    private BotProperties properties;

    @BeforeEach
    void setUp() {
        shellTool = mock(ToolComponent.class);
        when(shellTool.getToolName()).thenReturn("shell");
        when(shellTool.isEnabled()).thenReturn(true);

        dateTimeTool = mock(ToolComponent.class);
        when(dateTimeTool.getToolName()).thenReturn("datetime");
        when(dateTimeTool.isEnabled()).thenReturn(true);

        confirmationPolicy = mock(ToolConfirmationPolicy.class);
        confirmationPort = mock(ConfirmationPort.class);
        channelPort = mock(ChannelPort.class);
        when(channelPort.getChannelType()).thenReturn("telegram");
        when(channelPort.sendMessage(anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(null));

        properties = new BotProperties();
        properties.getAutoCompact().setMaxToolResultChars(100000);

        system = new ToolExecutionSystem(List.of(shellTool, dateTimeTool), confirmationPolicy, confirmationPort,
                properties, List.of(channelPort));
    }

    private AgentContext createContextWithToolCalls(List<Message.ToolCall> toolCalls) {
        AgentSession session = AgentSession.builder()
                .id("test-session")
                .chatId("chat1")
                .channelType("telegram")
                .messages(new ArrayList<>())
                .build();

        LlmResponse llmResponse = LlmResponse.builder()
                .content("Let me do that")
                .build();

        AgentContext context = AgentContext.builder()
                .session(session)
                .messages(new ArrayList<>())
                .build();

        context.setAttribute("llm.toolCalls", toolCalls);
        context.setAttribute("llm.response", llmResponse);
        return context;
    }

    @Test
    void confirmedToolCallExecutes() {
        Message.ToolCall toolCall = Message.ToolCall.builder()
                .id("tc1")
                .name("shell")
                .arguments(Map.of("command", "echo hello"))
                .build();

        when(confirmationPolicy.requiresConfirmation(toolCall)).thenReturn(true);
        when(confirmationPolicy.describeAction(toolCall)).thenReturn("Run command: echo hello");
        when(confirmationPort.isAvailable()).thenReturn(true);
        when(confirmationPort.requestConfirmation(eq("chat1"), eq("shell"), anyString()))
                .thenReturn(CompletableFuture.completedFuture(true));
        when(shellTool.execute(any())).thenReturn(CompletableFuture.completedFuture(ToolResult.success("hello\n")));

        AgentContext context = createContextWithToolCalls(List.of(toolCall));
        system.process(context);

        verify(shellTool).execute(any());
        // 1 assistant message + 1 tool result message
        assertEquals(2, context.getMessages().size());
        assertEquals("tool", context.getMessages().get(1).getRole());
        assertEquals("hello\n", context.getMessages().get(1).getContent());
    }

    @Test
    void deniedToolCallReturnsCancelled() {
        Message.ToolCall toolCall = Message.ToolCall.builder()
                .id("tc1")
                .name("shell")
                .arguments(Map.of("command", "rm -rf test"))
                .build();

        when(confirmationPolicy.requiresConfirmation(toolCall)).thenReturn(true);
        when(confirmationPolicy.describeAction(toolCall)).thenReturn("Run command: rm -rf test");
        when(confirmationPort.isAvailable()).thenReturn(true);
        when(confirmationPort.requestConfirmation(eq("chat1"), eq("shell"), anyString()))
                .thenReturn(CompletableFuture.completedFuture(false));

        AgentContext context = createContextWithToolCalls(List.of(toolCall));
        system.process(context);

        verify(shellTool, never()).execute(any());
        // 1 assistant message + 1 tool result message (cancelled)
        assertEquals(2, context.getMessages().size());
        assertEquals("Error: Cancelled by user", context.getMessages().get(1).getContent());
    }

    @Test
    void nonDestructiveToolSkipsConfirmation() {
        Message.ToolCall toolCall = Message.ToolCall.builder()
                .id("tc1")
                .name("datetime")
                .arguments(Map.of("operation", "now"))
                .build();

        when(confirmationPolicy.requiresConfirmation(toolCall)).thenReturn(false);
        when(dateTimeTool.execute(any()))
                .thenReturn(CompletableFuture.completedFuture(ToolResult.success("2026-02-05")));

        AgentContext context = createContextWithToolCalls(List.of(toolCall));
        system.process(context);

        verify(confirmationPort, never()).requestConfirmation(any(), any(), any());
        verify(dateTimeTool).execute(any());
    }

    @Test
    void confirmationUnavailableSkipsConfirmation() {
        Message.ToolCall toolCall = Message.ToolCall.builder()
                .id("tc1")
                .name("shell")
                .arguments(Map.of("command", "ls"))
                .build();

        when(confirmationPolicy.requiresConfirmation(toolCall)).thenReturn(true);
        when(confirmationPort.isAvailable()).thenReturn(false);
        when(shellTool.execute(any())).thenReturn(CompletableFuture.completedFuture(ToolResult.success("files")));

        AgentContext context = createContextWithToolCalls(List.of(toolCall));
        system.process(context);

        verify(confirmationPort, never()).requestConfirmation(any(), any(), any());
        verify(shellTool).execute(any());
    }

    @Test
    void disabledConfirmationSendsNotificationForNotableAction() {
        Message.ToolCall toolCall = Message.ToolCall.builder()
                .id("tc1")
                .name("shell")
                .arguments(Map.of("command", "echo hello"))
                .build();

        when(confirmationPolicy.requiresConfirmation(toolCall)).thenReturn(false);
        when(confirmationPolicy.isEnabled()).thenReturn(false);
        when(confirmationPolicy.isNotableAction(toolCall)).thenReturn(true);
        when(confirmationPolicy.describeAction(toolCall)).thenReturn("Run command: echo hello");
        when(shellTool.execute(any())).thenReturn(CompletableFuture.completedFuture(ToolResult.success("hello\n")));

        AgentContext context = createContextWithToolCalls(List.of(toolCall));
        system.process(context);

        verify(shellTool).execute(any());
        verify(confirmationPort, never()).requestConfirmation(any(), any(), any());
        verify(channelPort).sendMessage(eq("chat1"), contains("Run command: echo hello"));
    }

    @Test
    void disabledConfirmationNoNotificationForNonNotableAction() {
        Message.ToolCall toolCall = Message.ToolCall.builder()
                .id("tc1")
                .name("datetime")
                .arguments(Map.of("operation", "now"))
                .build();

        when(confirmationPolicy.requiresConfirmation(toolCall)).thenReturn(false);
        when(confirmationPolicy.isEnabled()).thenReturn(false);
        when(confirmationPolicy.isNotableAction(toolCall)).thenReturn(false);
        when(dateTimeTool.execute(any()))
                .thenReturn(CompletableFuture.completedFuture(ToolResult.success("2026-02-06")));

        AgentContext context = createContextWithToolCalls(List.of(toolCall));
        system.process(context);

        verify(dateTimeTool).execute(any());
        verify(channelPort, never()).sendMessage(anyString(), anyString());
    }

    @Test
    void enabledConfirmationNoNotificationForNotableAction() {
        Message.ToolCall toolCall = Message.ToolCall.builder()
                .id("tc1")
                .name("datetime")
                .arguments(Map.of("operation", "now"))
                .build();

        // Enabled but doesn't require confirmation (non-notable tool)
        when(confirmationPolicy.requiresConfirmation(toolCall)).thenReturn(false);
        when(confirmationPolicy.isEnabled()).thenReturn(true);
        when(dateTimeTool.execute(any()))
                .thenReturn(CompletableFuture.completedFuture(ToolResult.success("2026-02-06")));

        AgentContext context = createContextWithToolCalls(List.of(toolCall));
        system.process(context);

        verify(channelPort, never()).sendMessage(anyString(), anyString());
    }

    // ===== Tool Result Truncation Tests =====

    @Test
    void smallToolResultNotTruncated() {
        String result = system.truncateToolResult("short result", "test");
        assertEquals("short result", result);
    }

    @Test
    void nullToolResultPassesThrough() {
        assertNull(system.truncateToolResult(null, "test"));
    }

    @Test
    void largeToolResultTruncatedWithHint() {
        properties.getAutoCompact().setMaxToolResultChars(200);
        String largeContent = "x".repeat(500);

        String result = system.truncateToolResult(largeContent, "shell");

        assertTrue(result.length() <= 200 + 10, "Result should be approximately maxChars");
        assertTrue(result.contains("[OUTPUT TRUNCATED:"));
        assertTrue(result.contains("500 chars total"));
        assertTrue(result.contains("Try a more specific query"));
    }

    @Test
    void toolResultTruncationAppliedDuringExecution() {
        properties.getAutoCompact().setMaxToolResultChars(100);
        String hugeOutput = "data".repeat(100); // 400 chars

        Message.ToolCall toolCall = Message.ToolCall.builder()
                .id("tc1")
                .name("shell")
                .arguments(Map.of("command", "fetch_data"))
                .build();

        when(confirmationPolicy.requiresConfirmation(toolCall)).thenReturn(false);
        when(shellTool.execute(any())).thenReturn(
                CompletableFuture.completedFuture(ToolResult.success(hugeOutput)));

        AgentContext context = createContextWithToolCalls(List.of(toolCall));
        system.process(context);

        // Tool result message should be truncated
        Message toolMessage = context.getMessages().get(1); // 0=assistant, 1=tool
        assertTrue(toolMessage.getContent().contains("[OUTPUT TRUNCATED:"));
        assertTrue(toolMessage.getContent().length() < hugeOutput.length());
    }

    @Test
    void disabledTruncationWhenMaxCharsZero() {
        properties.getAutoCompact().setMaxToolResultChars(0);
        String largeContent = "x".repeat(500);

        String result = system.truncateToolResult(largeContent, "test");
        assertEquals(largeContent, result); // Not truncated
    }

    // ===== Attachment Extraction Tests =====

    @Test
    void extractAttachmentFromDirectAttachment() {
        Attachment attachment = Attachment.builder()
                .type(Attachment.Type.IMAGE)
                .data(new byte[] { 1, 2, 3 })
                .filename("test.png")
                .mimeType("image/png")
                .build();

        ToolResult result = ToolResult.success("done", Map.of("attachment", attachment));
        AgentContext context = createContextWithToolCalls(List.of());

        system.extractAttachment(context, result, "browse");

        @SuppressWarnings("unchecked")
        List<Attachment> pending = context.getAttribute("pendingAttachments");
        assertNotNull(pending);
        assertEquals(1, pending.size());
        assertEquals("test.png", pending.get(0).getFilename());
        assertEquals(Attachment.Type.IMAGE, pending.get(0).getType());
    }

    @Test
    void extractAttachmentFromScreenshotBase64() {
        byte[] imageBytes = new byte[] { 10, 20, 30 };
        String b64 = Base64.getEncoder().encodeToString(imageBytes);

        ToolResult result = ToolResult.success("captured", Map.of("screenshot_base64", b64, "format", "png"));
        AgentContext context = createContextWithToolCalls(List.of());

        system.extractAttachment(context, result, "browse");

        @SuppressWarnings("unchecked")
        List<Attachment> pending = context.getAttribute("pendingAttachments");
        assertNotNull(pending);
        assertEquals(1, pending.size());
        assertEquals("screenshot.png", pending.get(0).getFilename());
        assertEquals("image/png", pending.get(0).getMimeType());
        assertArrayEquals(imageBytes, pending.get(0).getData());
    }

    @Test
    void extractAttachmentFromFileBytes() {
        byte[] fileBytes = new byte[] { 40, 50, 60 };

        Map<String, Object> data = new HashMap<>();
        data.put("file_bytes", fileBytes);
        data.put("filename", "report.pdf");
        data.put("mime_type", "application/pdf");

        ToolResult result = ToolResult.success("queued", data);
        AgentContext context = createContextWithToolCalls(List.of());

        system.extractAttachment(context, result, "filesystem");

        @SuppressWarnings("unchecked")
        List<Attachment> pending = context.getAttribute("pendingAttachments");
        assertNotNull(pending);
        assertEquals(1, pending.size());
        assertEquals("report.pdf", pending.get(0).getFilename());
        assertEquals(Attachment.Type.DOCUMENT, pending.get(0).getType());
        assertArrayEquals(fileBytes, pending.get(0).getData());
    }

    @Test
    void extractAttachmentImageFromFileBytes() {
        byte[] fileBytes = new byte[] { 40, 50, 60 };

        Map<String, Object> data = new HashMap<>();
        data.put("file_bytes", fileBytes);
        data.put("filename", "photo.jpg");
        data.put("mime_type", "image/jpeg");

        ToolResult result = ToolResult.success("queued", data);
        AgentContext context = createContextWithToolCalls(List.of());

        system.extractAttachment(context, result, "filesystem");

        @SuppressWarnings("unchecked")
        List<Attachment> pending = context.getAttribute("pendingAttachments");
        assertNotNull(pending);
        assertEquals(Attachment.Type.IMAGE, pending.get(0).getType());
    }

    @Test
    void extractAttachmentIgnoresFailedResult() {
        ToolResult result = ToolResult.failure("something went wrong");
        AgentContext context = createContextWithToolCalls(List.of());

        system.extractAttachment(context, result, "browse");

        List<?> pending = context.getAttribute("pendingAttachments");
        assertNull(pending);
    }

    @Test
    void extractAttachmentIgnoresNonMapData() {
        ToolResult result = ToolResult.success("done", "just a string");
        AgentContext context = createContextWithToolCalls(List.of());

        system.extractAttachment(context, result, "browse");

        List<?> pending = context.getAttribute("pendingAttachments");
        assertNull(pending);
    }

    @Test
    void extractAttachmentMultipleAttachmentsAccumulate() {
        Attachment a1 = Attachment.builder()
                .type(Attachment.Type.IMAGE)
                .data(new byte[] { 1 })
                .filename("img1.png")
                .mimeType("image/png")
                .build();
        Attachment a2 = Attachment.builder()
                .type(Attachment.Type.DOCUMENT)
                .data(new byte[] { 2 })
                .filename("doc.pdf")
                .mimeType("application/pdf")
                .build();

        AgentContext context = createContextWithToolCalls(List.of());

        system.extractAttachment(context, ToolResult.success("ok", Map.of("attachment", a1)), "t1");
        system.extractAttachment(context, ToolResult.success("ok", Map.of("attachment", a2)), "t2");

        @SuppressWarnings("unchecked")
        List<Attachment> pending = context.getAttribute("pendingAttachments");
        assertNotNull(pending);
        assertEquals(2, pending.size());
    }

    @Test
    void extractAttachmentPrefersDirectAttachmentOverBase64() {
        Attachment explicit = Attachment.builder()
                .type(Attachment.Type.IMAGE)
                .data(new byte[] { 1, 2, 3 })
                .filename("explicit.png")
                .mimeType("image/png")
                .build();

        String b64 = Base64.getEncoder().encodeToString(new byte[] { 10, 20 });
        Map<String, Object> data = new HashMap<>();
        data.put("attachment", explicit);
        data.put("screenshot_base64", b64);

        ToolResult result = ToolResult.success("ok", data);
        AgentContext context = createContextWithToolCalls(List.of());

        system.extractAttachment(context, result, "browse");

        @SuppressWarnings("unchecked")
        List<Attachment> pending = context.getAttribute("pendingAttachments");
        assertEquals(1, pending.size());
        assertEquals("explicit.png", pending.get(0).getFilename());
    }

    @Test
    void attachmentExtractedDuringToolExecution() {
        Attachment attachment = Attachment.builder()
                .type(Attachment.Type.IMAGE)
                .data(new byte[] { 1, 2, 3 })
                .filename("screenshot.png")
                .mimeType("image/png")
                .build();

        Message.ToolCall toolCall = Message.ToolCall.builder()
                .id("tc1")
                .name("datetime")
                .arguments(Map.of("operation", "now"))
                .build();

        when(confirmationPolicy.requiresConfirmation(toolCall)).thenReturn(false);
        when(dateTimeTool.execute(any())).thenReturn(
                CompletableFuture.completedFuture(
                        ToolResult.success("done", Map.of("attachment", attachment))));

        AgentContext context = createContextWithToolCalls(List.of(toolCall));
        system.process(context);

        @SuppressWarnings("unchecked")
        List<Attachment> pending = context.getAttribute("pendingAttachments");
        assertNotNull(pending);
        assertEquals(1, pending.size());
        assertEquals("screenshot.png", pending.get(0).getFilename());
    }

    // ===== Edge Cases: Tool Execution =====

    @Test
    void unknownToolReturnsError() {
        Message.ToolCall toolCall = Message.ToolCall.builder()
                .id("tc1")
                .name("nonexistent_tool")
                .arguments(Map.of())
                .build();

        when(confirmationPolicy.requiresConfirmation(toolCall)).thenReturn(false);

        AgentContext context = createContextWithToolCalls(List.of(toolCall));
        system.process(context);

        assertEquals(2, context.getMessages().size());
        assertTrue(context.getMessages().get(1).getContent().contains("Unknown tool"));
        assertTrue(context.getMessages().get(1).getContent().contains("nonexistent_tool"));
    }

    @Test
    void disabledToolReturnsError() {
        when(shellTool.isEnabled()).thenReturn(false);

        Message.ToolCall toolCall = Message.ToolCall.builder()
                .id("tc1")
                .name("shell")
                .arguments(Map.of("command", "ls"))
                .build();

        when(confirmationPolicy.requiresConfirmation(toolCall)).thenReturn(false);

        AgentContext context = createContextWithToolCalls(List.of(toolCall));
        system.process(context);

        assertEquals(2, context.getMessages().size());
        assertTrue(context.getMessages().get(1).getContent().contains("disabled"));
    }

    @Test
    void toolExecutionExceptionReturnsError() {
        Message.ToolCall toolCall = Message.ToolCall.builder()
                .id("tc1")
                .name("shell")
                .arguments(Map.of("command", "bad"))
                .build();

        when(confirmationPolicy.requiresConfirmation(toolCall)).thenReturn(false);
        when(shellTool.execute(any()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("execution failed")));

        AgentContext context = createContextWithToolCalls(List.of(toolCall));
        system.process(context);

        assertEquals(2, context.getMessages().size());
        assertTrue(context.getMessages().get(1).getContent().contains("execution failed"));
    }

    @Test
    void noToolCallsDoesNothing() {
        AgentContext context = createContextWithToolCalls(List.of());
        context.setAttribute("llm.toolCalls", null);

        AgentContext result = system.process(context);

        assertSame(context, result);
        assertTrue(context.getMessages().isEmpty());
    }

    @Test
    void multipleToolCallsAllExecuted() {
        Message.ToolCall tc1 = Message.ToolCall.builder()
                .id("tc1")
                .name("shell")
                .arguments(Map.of("command", "ls"))
                .build();
        Message.ToolCall tc2 = Message.ToolCall.builder()
                .id("tc2")
                .name("datetime")
                .arguments(Map.of("operation", "now"))
                .build();

        when(confirmationPolicy.requiresConfirmation(any())).thenReturn(false);
        when(shellTool.execute(any())).thenReturn(CompletableFuture.completedFuture(ToolResult.success("files")));
        when(dateTimeTool.execute(any()))
                .thenReturn(CompletableFuture.completedFuture(ToolResult.success("2026-01-01")));

        AgentContext context = createContextWithToolCalls(List.of(tc1, tc2));
        system.process(context);

        // 1 assistant + 2 tool results
        assertEquals(3, context.getMessages().size());
        verify(shellTool).execute(any());
        verify(dateTimeTool).execute(any());
        assertTrue((Boolean) context.getAttribute("tools.executed"));
    }

    @Test
    void toolNameSanitization() {
        Message.ToolCall toolCall = Message.ToolCall.builder()
                .id("tc1")
                .name("shell<|channel|>commentary")
                .arguments(Map.of("command", "ls"))
                .build();

        when(confirmationPolicy.requiresConfirmation(toolCall)).thenReturn(false);
        when(shellTool.execute(any())).thenReturn(CompletableFuture.completedFuture(ToolResult.success("ok")));

        AgentContext context = createContextWithToolCalls(List.of(toolCall));
        system.process(context);

        verify(shellTool).execute(any());
    }

    @Test
    void longToolCallIdNormalized() {
        String longId = "call_" + "a".repeat(50);
        Message.ToolCall toolCall = Message.ToolCall.builder()
                .id(longId)
                .name("datetime")
                .arguments(Map.of("operation", "now"))
                .build();

        when(confirmationPolicy.requiresConfirmation(toolCall)).thenReturn(false);
        when(dateTimeTool.execute(any())).thenReturn(CompletableFuture.completedFuture(ToolResult.success("ok")));

        AgentContext context = createContextWithToolCalls(List.of(toolCall));
        system.process(context);

        // Tool result message should have normalized (shorter) ID
        Message toolMsg = context.getMessages().get(1);
        assertTrue(toolMsg.getToolCallId().length() <= 40);
    }

    @Test
    void confirmationFailureDefaultsToDeny() {
        Message.ToolCall toolCall = Message.ToolCall.builder()
                .id("tc1")
                .name("shell")
                .arguments(Map.of("command", "rm test"))
                .build();

        when(confirmationPolicy.requiresConfirmation(toolCall)).thenReturn(true);
        when(confirmationPolicy.describeAction(toolCall)).thenReturn("Delete file");
        when(confirmationPort.isAvailable()).thenReturn(true);
        when(confirmationPort.requestConfirmation(eq("chat1"), eq("shell"), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("timeout")));

        AgentContext context = createContextWithToolCalls(List.of(toolCall));
        system.process(context);

        verify(shellTool, never()).execute(any());
        assertTrue(context.getMessages().get(1).getContent().contains("Cancelled by user"));
    }

    @Test
    void toolResultTruncationAtExactBoundary() {
        properties.getAutoCompact().setMaxToolResultChars(50);
        String exactContent = "x".repeat(50);

        String result = system.truncateToolResult(exactContent, "test");

        assertEquals(exactContent, result); // exactly at boundary, not truncated
    }

    @Test
    void extractAttachmentInvalidBase64() {
        ToolResult result = ToolResult.success("captured", Map.of("screenshot_base64", "not-valid-base64!!!"));
        AgentContext context = createContextWithToolCalls(List.of());

        system.extractAttachment(context, result, "browse");

        List<?> pending = context.getAttribute("pendingAttachments");
        assertNull(pending); // invalid base64 should be skipped
    }

    @Test
    void extractAttachmentNullResult() {
        AgentContext context = createContextWithToolCalls(List.of());

        system.extractAttachment(context, null, "browse");

        assertNull(context.getAttribute("pendingAttachments"));
    }

    @Test
    void toolFailureResultIncludesOutputWhenPresent() {
        Message.ToolCall toolCall = Message.ToolCall.builder()
                .id("tc1")
                .name("shell")
                .arguments(Map.of("command", "bad-cmd"))
                .build();

        when(confirmationPolicy.requiresConfirmation(toolCall)).thenReturn(false);
        when(shellTool.execute(any())).thenReturn(CompletableFuture.completedFuture(
                ToolResult.builder()
                        .success(false)
                        .error("Exit code 1")
                        .output("stderr: command not found")
                        .build()));

        AgentContext context = createContextWithToolCalls(List.of(toolCall));
        system.process(context);

        // When output is present, it should be used even for failed results
        assertEquals("stderr: command not found", context.getMessages().get(1).getContent());
    }
}
