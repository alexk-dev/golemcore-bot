package me.golemcore.bot.domain.system;

import me.golemcore.bot.domain.component.ToolComponent;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.Attachment;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.ToolResult;
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

    private static final String TOOL_CALL_ID = "tc1";
    private static final String TOOL_SHELL = "shell";
    private static final String TOOL_DATETIME = "datetime";
    private static final String CHAT_ID = "chat1";
    private static final String SESSION_ID = "test-session";
    private static final String CHANNEL_TYPE = "telegram";
    private static final String ARG_COMMAND = "command";
    private static final String ARG_OPERATION = "operation";
    private static final String ARG_NOW = "now";
    private static final String ATTR_PENDING_ATTACHMENTS = "pendingAttachments";
    private static final String MIME_IMAGE_PNG = "image/png";
    private static final String MIME_APP_PDF = "application/pdf";
    private static final String TOOL_BROWSE = "browse";
    private static final String TOOL_NAME_TEST = "test";
    private static final String KEY_ATTACHMENT = "attachment";
    private static final String SUPPRESS_UNCHECKED = "unchecked";

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
        when(shellTool.getToolName()).thenReturn(TOOL_SHELL);
        when(shellTool.isEnabled()).thenReturn(true);

        dateTimeTool = mock(ToolComponent.class);
        when(dateTimeTool.getToolName()).thenReturn(TOOL_DATETIME);
        when(dateTimeTool.isEnabled()).thenReturn(true);

        confirmationPolicy = mock(ToolConfirmationPolicy.class);
        confirmationPort = mock(ConfirmationPort.class);
        channelPort = mock(ChannelPort.class);
        when(channelPort.getChannelType()).thenReturn(CHANNEL_TYPE);
        when(channelPort.sendMessage(anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(null));

        properties = new BotProperties();
        properties.getAutoCompact().setMaxToolResultChars(100000);

        system = new ToolExecutionSystem(List.of(shellTool, dateTimeTool), confirmationPolicy, confirmationPort,
                properties, List.of(channelPort));
    }

    private AgentContext createContextWithToolCalls(List<Message.ToolCall> toolCalls) {
        AgentSession session = AgentSession.builder()
                .id(SESSION_ID)
                .chatId(CHAT_ID)
                .channelType(CHANNEL_TYPE)
                .messages(new ArrayList<>())
                .build();

        LlmResponse llmResponse = LlmResponse.builder()
                .content("Let me do that")
                .build();

        AgentContext context = AgentContext.builder()
                .session(session)
                .messages(new ArrayList<>())
                .build();

        context.setAttribute(ContextAttributes.LLM_TOOL_CALLS, toolCalls);
        context.setAttribute(ContextAttributes.LLM_RESPONSE, llmResponse);
        return context;
    }

    @Test
    void confirmedToolCallExecutes() {
        Message.ToolCall toolCall = Message.ToolCall.builder()
                .id(TOOL_CALL_ID)
                .name(TOOL_SHELL)
                .arguments(Map.of(ARG_COMMAND, "echo hello"))
                .build();

        when(confirmationPolicy.requiresConfirmation(toolCall)).thenReturn(true);
        when(confirmationPolicy.describeAction(toolCall)).thenReturn("Run command: echo hello");
        when(confirmationPort.isAvailable()).thenReturn(true);
        when(confirmationPort.requestConfirmation(eq(CHAT_ID), eq(TOOL_SHELL), anyString()))
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
                .id(TOOL_CALL_ID)
                .name(TOOL_SHELL)
                .arguments(Map.of(ARG_COMMAND, "rm -rf test"))
                .build();

        when(confirmationPolicy.requiresConfirmation(toolCall)).thenReturn(true);
        when(confirmationPolicy.describeAction(toolCall)).thenReturn("Run command: rm -rf test");
        when(confirmationPort.isAvailable()).thenReturn(true);
        when(confirmationPort.requestConfirmation(eq(CHAT_ID), eq(TOOL_SHELL), anyString()))
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
                .id(TOOL_CALL_ID)
                .name(TOOL_DATETIME)
                .arguments(Map.of(ARG_OPERATION, ARG_NOW))
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
                .id(TOOL_CALL_ID)
                .name(TOOL_SHELL)
                .arguments(Map.of(ARG_COMMAND, "ls"))
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
                .id(TOOL_CALL_ID)
                .name(TOOL_SHELL)
                .arguments(Map.of(ARG_COMMAND, "echo hello"))
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
        verify(channelPort).sendMessage(eq(CHAT_ID), contains("Run command: echo hello"));
    }

    @Test
    void disabledConfirmationNoNotificationForNonNotableAction() {
        Message.ToolCall toolCall = Message.ToolCall.builder()
                .id(TOOL_CALL_ID)
                .name(TOOL_DATETIME)
                .arguments(Map.of(ARG_OPERATION, ARG_NOW))
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
                .id(TOOL_CALL_ID)
                .name(TOOL_DATETIME)
                .arguments(Map.of(ARG_OPERATION, ARG_NOW))
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
        String result = system.truncateToolResult("short result", TOOL_NAME_TEST);
        assertEquals("short result", result);
    }

    @Test
    void nullToolResultPassesThrough() {
        assertNull(system.truncateToolResult(null, TOOL_NAME_TEST));
    }

    @Test
    void largeToolResultTruncatedWithHint() {
        properties.getAutoCompact().setMaxToolResultChars(200);
        String largeContent = "x".repeat(500);

        String result = system.truncateToolResult(largeContent, TOOL_SHELL);

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
                .id(TOOL_CALL_ID)
                .name(TOOL_SHELL)
                .arguments(Map.of(ARG_COMMAND, "fetch_data"))
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

        String result = system.truncateToolResult(largeContent, TOOL_NAME_TEST);
        assertEquals(largeContent, result); // Not truncated
    }

    // ===== Attachment Extraction Tests =====

    @Test
    void extractAttachmentFromDirectAttachment() {
        Attachment attachment = Attachment.builder()
                .type(Attachment.Type.IMAGE)
                .data(new byte[] { 1, 2, 3 })
                .filename("test.png")
                .mimeType(MIME_IMAGE_PNG)
                .build();

        ToolResult result = ToolResult.success("done", Map.of(KEY_ATTACHMENT, attachment));
        AgentContext context = createContextWithToolCalls(List.of());

        system.extractAttachment(context, result, TOOL_BROWSE);

        @SuppressWarnings(SUPPRESS_UNCHECKED)
        List<Attachment> pending = context.getAttribute(ATTR_PENDING_ATTACHMENTS);
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

        system.extractAttachment(context, result, TOOL_BROWSE);

        @SuppressWarnings(SUPPRESS_UNCHECKED)
        List<Attachment> pending = context.getAttribute(ATTR_PENDING_ATTACHMENTS);
        assertNotNull(pending);
        assertEquals(1, pending.size());
        assertEquals("screenshot.png", pending.get(0).getFilename());
        assertEquals(MIME_IMAGE_PNG, pending.get(0).getMimeType());
        assertArrayEquals(imageBytes, pending.get(0).getData());
    }

    @Test
    void extractAttachmentFromFileBytes() {
        byte[] fileBytes = new byte[] { 40, 50, 60 };

        Map<String, Object> data = new HashMap<>();
        data.put("file_bytes", fileBytes);
        data.put("filename", "report.pdf");
        data.put("mime_type", MIME_APP_PDF);

        ToolResult result = ToolResult.success("queued", data);
        AgentContext context = createContextWithToolCalls(List.of());

        system.extractAttachment(context, result, "filesystem");

        @SuppressWarnings(SUPPRESS_UNCHECKED)
        List<Attachment> pending = context.getAttribute(ATTR_PENDING_ATTACHMENTS);
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

        @SuppressWarnings(SUPPRESS_UNCHECKED)
        List<Attachment> pending = context.getAttribute(ATTR_PENDING_ATTACHMENTS);
        assertNotNull(pending);
        assertEquals(Attachment.Type.IMAGE, pending.get(0).getType());
    }

    @Test
    void extractAttachmentIgnoresFailedResult() {
        ToolResult result = ToolResult.failure("something went wrong");
        AgentContext context = createContextWithToolCalls(List.of());

        system.extractAttachment(context, result, TOOL_BROWSE);

        List<?> pending = context.getAttribute(ATTR_PENDING_ATTACHMENTS);
        assertNull(pending);
    }

    @Test
    void extractAttachmentIgnoresNonMapData() {
        ToolResult result = ToolResult.success("done", "just a string");
        AgentContext context = createContextWithToolCalls(List.of());

        system.extractAttachment(context, result, TOOL_BROWSE);

        List<?> pending = context.getAttribute(ATTR_PENDING_ATTACHMENTS);
        assertNull(pending);
    }

    @Test
    void extractAttachmentMultipleAttachmentsAccumulate() {
        Attachment a1 = Attachment.builder()
                .type(Attachment.Type.IMAGE)
                .data(new byte[] { 1 })
                .filename("img1.png")
                .mimeType(MIME_IMAGE_PNG)
                .build();
        Attachment a2 = Attachment.builder()
                .type(Attachment.Type.DOCUMENT)
                .data(new byte[] { 2 })
                .filename("doc.pdf")
                .mimeType(MIME_APP_PDF)
                .build();

        AgentContext context = createContextWithToolCalls(List.of());

        system.extractAttachment(context, ToolResult.success("ok", Map.of(KEY_ATTACHMENT, a1)), "t1");
        system.extractAttachment(context, ToolResult.success("ok", Map.of(KEY_ATTACHMENT, a2)), "t2");

        @SuppressWarnings(SUPPRESS_UNCHECKED)
        List<Attachment> pending = context.getAttribute(ATTR_PENDING_ATTACHMENTS);
        assertNotNull(pending);
        assertEquals(2, pending.size());
    }

    @Test
    void extractAttachmentPrefersDirectAttachmentOverBase64() {
        Attachment explicit = Attachment.builder()
                .type(Attachment.Type.IMAGE)
                .data(new byte[] { 1, 2, 3 })
                .filename("explicit.png")
                .mimeType(MIME_IMAGE_PNG)
                .build();

        String b64 = Base64.getEncoder().encodeToString(new byte[] { 10, 20 });
        Map<String, Object> data = new HashMap<>();
        data.put(KEY_ATTACHMENT, explicit);
        data.put("screenshot_base64", b64);

        ToolResult result = ToolResult.success("ok", data);
        AgentContext context = createContextWithToolCalls(List.of());

        system.extractAttachment(context, result, TOOL_BROWSE);

        @SuppressWarnings(SUPPRESS_UNCHECKED)
        List<Attachment> pending = context.getAttribute(ATTR_PENDING_ATTACHMENTS);
        assertEquals(1, pending.size());
        assertEquals("explicit.png", pending.get(0).getFilename());
    }

    @Test
    void attachmentExtractedDuringToolExecution() {
        Attachment attachment = Attachment.builder()
                .type(Attachment.Type.IMAGE)
                .data(new byte[] { 1, 2, 3 })
                .filename("screenshot.png")
                .mimeType(MIME_IMAGE_PNG)
                .build();

        Message.ToolCall toolCall = Message.ToolCall.builder()
                .id(TOOL_CALL_ID)
                .name(TOOL_DATETIME)
                .arguments(Map.of(ARG_OPERATION, ARG_NOW))
                .build();

        when(confirmationPolicy.requiresConfirmation(toolCall)).thenReturn(false);
        when(dateTimeTool.execute(any())).thenReturn(
                CompletableFuture.completedFuture(
                        ToolResult.success("done", Map.of(KEY_ATTACHMENT, attachment))));

        AgentContext context = createContextWithToolCalls(List.of(toolCall));
        system.process(context);

        @SuppressWarnings(SUPPRESS_UNCHECKED)
        List<Attachment> pending = context.getAttribute(ATTR_PENDING_ATTACHMENTS);
        assertNotNull(pending);
        assertEquals(1, pending.size());
        assertEquals("screenshot.png", pending.get(0).getFilename());
    }

    // ===== Edge Cases: Tool Execution =====

    @Test
    void unknownToolReturnsError() {
        Message.ToolCall toolCall = Message.ToolCall.builder()
                .id(TOOL_CALL_ID)
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
                .id(TOOL_CALL_ID)
                .name(TOOL_SHELL)
                .arguments(Map.of(ARG_COMMAND, "ls"))
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
                .id(TOOL_CALL_ID)
                .name(TOOL_SHELL)
                .arguments(Map.of(ARG_COMMAND, "bad"))
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
        context.setAttribute(ContextAttributes.LLM_TOOL_CALLS, null);

        AgentContext result = system.process(context);

        assertSame(context, result);
        assertTrue(context.getMessages().isEmpty());
    }

    @Test
    void multipleToolCallsAllExecuted() {
        Message.ToolCall tc1 = Message.ToolCall.builder()
                .id(TOOL_CALL_ID)
                .name(TOOL_SHELL)
                .arguments(Map.of(ARG_COMMAND, "ls"))
                .build();
        Message.ToolCall tc2 = Message.ToolCall.builder()
                .id("tc2")
                .name(TOOL_DATETIME)
                .arguments(Map.of(ARG_OPERATION, ARG_NOW))
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
        assertTrue((Boolean) context.getAttribute(ContextAttributes.TOOLS_EXECUTED));
    }

    @Test
    void toolNameSanitization() {
        Message.ToolCall toolCall = Message.ToolCall.builder()
                .id(TOOL_CALL_ID)
                .name("shell<|channel|>commentary")
                .arguments(Map.of(ARG_COMMAND, "ls"))
                .build();

        when(confirmationPolicy.requiresConfirmation(toolCall)).thenReturn(false);
        when(shellTool.execute(any())).thenReturn(CompletableFuture.completedFuture(ToolResult.success("ok")));

        AgentContext context = createContextWithToolCalls(List.of(toolCall));
        system.process(context);

        verify(shellTool).execute(any());
    }

    @Test
    void confirmationFailureDefaultsToDeny() {
        Message.ToolCall toolCall = Message.ToolCall.builder()
                .id(TOOL_CALL_ID)
                .name(TOOL_SHELL)
                .arguments(Map.of(ARG_COMMAND, "rm test"))
                .build();

        when(confirmationPolicy.requiresConfirmation(toolCall)).thenReturn(true);
        when(confirmationPolicy.describeAction(toolCall)).thenReturn("Delete file");
        when(confirmationPort.isAvailable()).thenReturn(true);
        when(confirmationPort.requestConfirmation(eq(CHAT_ID), eq(TOOL_SHELL), anyString()))
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

        String result = system.truncateToolResult(exactContent, TOOL_NAME_TEST);

        assertEquals(exactContent, result); // exactly at boundary, not truncated
    }

    @Test
    void extractAttachmentInvalidBase64() {
        ToolResult result = ToolResult.success("captured", Map.of("screenshot_base64", "not-valid-base64!!!"));
        AgentContext context = createContextWithToolCalls(List.of());

        system.extractAttachment(context, result, TOOL_BROWSE);

        List<?> pending = context.getAttribute(ATTR_PENDING_ATTACHMENTS);
        assertNull(pending); // invalid base64 should be skipped
    }

    @Test
    void extractAttachmentNullResult() {
        AgentContext context = createContextWithToolCalls(List.of());

        system.extractAttachment(context, null, TOOL_BROWSE);

        assertNull(context.getAttribute(ATTR_PENDING_ATTACHMENTS));
    }

    @Test
    void toolFailureResultIncludesOutputWhenPresent() {
        Message.ToolCall toolCall = Message.ToolCall.builder()
                .id(TOOL_CALL_ID)
                .name(TOOL_SHELL)
                .arguments(Map.of(ARG_COMMAND, "bad-cmd"))
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
