package me.golemcore.bot.domain.service;

import me.golemcore.bot.domain.component.ToolComponent;
import me.golemcore.bot.domain.loop.AgentContextHolder;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.Attachment;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.ToolFailureKind;
import me.golemcore.bot.domain.model.ToolResult;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.port.inbound.ChannelPort;
import me.golemcore.bot.port.outbound.ConfirmationPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ToolCallExecutionServiceTest {

    private static final String TOOL_NAME = "test_tool";
    private static final String TOOL_CALL_ID = "call_123";
    private static final String CHAT_ID = "chat_456";
    private static final String CHANNEL_TYPE = "telegram";
    private static final int MAX_TOOL_RESULT_CHARS = 500;

    private ToolComponent toolComponent;
    private ToolConfirmationPolicy confirmationPolicy;
    private ConfirmationPort confirmationPort;
    private BotProperties properties;
    private ChannelPort channelPort;
    private ToolCallExecutionService service;

    @BeforeEach
    void setUp() {
        toolComponent = mock(ToolComponent.class);
        confirmationPolicy = mock(ToolConfirmationPolicy.class);
        confirmationPort = mock(ConfirmationPort.class);
        properties = new BotProperties();
        properties.getAutoCompact().setMaxToolResultChars(MAX_TOOL_RESULT_CHARS);
        channelPort = mock(ChannelPort.class);

        when(toolComponent.getToolName()).thenReturn(TOOL_NAME);
        when(toolComponent.isEnabled()).thenReturn(true);
        when(channelPort.getChannelType()).thenReturn(CHANNEL_TYPE);
        when(confirmationPort.isAvailable()).thenReturn(false);
        when(confirmationPolicy.requiresConfirmation(any())).thenReturn(false);
        when(confirmationPolicy.isEnabled()).thenReturn(true);

        service = new ToolCallExecutionService(
                List.of(toolComponent),
                confirmationPolicy,
                confirmationPort,
                properties,
                List.of(channelPort));
    }

    @AfterEach
    void tearDown() {
        AgentContextHolder.clear();
    }

    // ==================== execute: happy path ====================

    @Test
    void shouldExecuteToolCallSuccessfully() {
        // Arrange
        AgentContext context = buildContext();
        Message.ToolCall toolCall = buildToolCall(TOOL_NAME, Map.of("key", "value"));
        ToolResult expectedResult = ToolResult.success("Tool executed OK");
        when(toolComponent.execute(any())).thenReturn(CompletableFuture.completedFuture(expectedResult));

        // Act
        ToolCallExecutionResult result = service.execute(context, toolCall);

        // Assert
        assertNotNull(result);
        assertEquals(TOOL_CALL_ID, result.toolCallId());
        assertEquals(TOOL_NAME, result.toolName());
        assertTrue(result.toolResult().isSuccess());
        assertEquals("Tool executed OK", result.toolMessageContent());
        assertNull(result.extractedAttachment());
        verify(toolComponent).execute(Map.of("key", "value"));
    }

    // ==================== execute: tool not found ====================

    @Test
    void shouldReturnErrorWhenToolNotFound() {
        // Arrange
        AgentContext context = buildContext();
        Message.ToolCall toolCall = buildToolCall("nonexistent_tool", Map.of());

        // Act
        ToolCallExecutionResult result = service.execute(context, toolCall);

        // Assert
        assertNotNull(result);
        assertEquals("nonexistent_tool", result.toolName());
        ToolResult toolResult = result.toolResult();
        assertTrue(!toolResult.isSuccess());
        assertEquals(ToolFailureKind.POLICY_DENIED, toolResult.getFailureKind());
        assertTrue(toolResult.getError().contains("Unknown tool: nonexistent_tool"));
        assertTrue(toolResult.getError().contains("Available tools:"));
    }

    // ==================== execute: tool disabled ====================

    @Test
    void shouldReturnErrorWhenToolDisabled() {
        // Arrange
        AgentContext context = buildContext();
        Message.ToolCall toolCall = buildToolCall(TOOL_NAME, Map.of());
        when(toolComponent.isEnabled()).thenReturn(false);

        // Act
        ToolCallExecutionResult result = service.execute(context, toolCall);

        // Assert
        assertNotNull(result);
        ToolResult toolResult = result.toolResult();
        assertTrue(!toolResult.isSuccess());
        assertEquals(ToolFailureKind.POLICY_DENIED, toolResult.getFailureKind());
        assertTrue(toolResult.getError().contains("Tool is disabled"));
    }

    // ==================== truncation ====================

    @Test
    void shouldTruncateLongToolResults() {
        // Arrange
        AgentContext context = buildContext();
        Message.ToolCall toolCall = buildToolCall(TOOL_NAME, Map.of());
        String longOutput = "x".repeat(MAX_TOOL_RESULT_CHARS + 1000);
        ToolResult successResult = ToolResult.success(longOutput);
        when(toolComponent.execute(any())).thenReturn(CompletableFuture.completedFuture(successResult));

        // Act
        ToolCallExecutionResult result = service.execute(context, toolCall);

        // Assert
        assertNotNull(result);
        assertTrue(result.toolMessageContent().length() <= MAX_TOOL_RESULT_CHARS + 10,
                "Truncated content should be around maxToolResultChars");
        assertTrue(result.toolMessageContent().contains("[OUTPUT TRUNCATED:"));
    }

    @Test
    void shouldPreserveShortToolResults() {
        // Arrange
        AgentContext context = buildContext();
        Message.ToolCall toolCall = buildToolCall(TOOL_NAME, Map.of());
        String shortOutput = "short result";
        ToolResult successResult = ToolResult.success(shortOutput);
        when(toolComponent.execute(any())).thenReturn(CompletableFuture.completedFuture(successResult));

        // Act
        ToolCallExecutionResult result = service.execute(context, toolCall);

        // Assert
        assertNotNull(result);
        assertEquals("short result", result.toolMessageContent());
    }

    // ==================== buildToolMessageContent ====================

    @Test
    void shouldBuildSuccessMessageContent() {
        // Arrange
        AgentContext context = buildContext();
        Message.ToolCall toolCall = buildToolCall(TOOL_NAME, Map.of());
        ToolResult successResult = ToolResult.success("Success output text");
        when(toolComponent.execute(any())).thenReturn(CompletableFuture.completedFuture(successResult));

        // Act
        ToolCallExecutionResult result = service.execute(context, toolCall);

        // Assert
        assertEquals("Success output text", result.toolMessageContent());
    }

    @Test
    void shouldBuildErrorMessageContent() {
        // Arrange
        AgentContext context = buildContext();
        Message.ToolCall toolCall = buildToolCall(TOOL_NAME, Map.of());
        ToolResult failureResult = ToolResult.failure("Something went wrong");
        when(toolComponent.execute(any())).thenReturn(CompletableFuture.completedFuture(failureResult));

        // Act
        ToolCallExecutionResult result = service.execute(context, toolCall);

        // Assert
        assertTrue(result.toolMessageContent().contains("Error: Something went wrong"));
    }

    // ==================== registerTool / getTool ====================

    @Test
    void shouldRegisterAndGetTool() {
        // Arrange
        ToolComponent newTool = mock(ToolComponent.class);
        when(newTool.getToolName()).thenReturn("new_tool");

        // Act
        service.registerTool(newTool);
        ToolComponent retrieved = service.getTool("new_tool");

        // Assert
        assertNotNull(retrieved);
        assertEquals(newTool, retrieved);
    }

    @Test
    void shouldReturnNullForUnknownTool() {
        // Act
        ToolComponent retrieved = service.getTool("nonexistent");

        // Assert
        assertNull(retrieved);
    }

    // ==================== unregisterTools ====================

    @Test
    void shouldUnregisterTools() {
        // Arrange — the tool is registered during construction
        assertNotNull(service.getTool(TOOL_NAME));

        // Act
        service.unregisterTools(List.of(TOOL_NAME));

        // Assert
        assertNull(service.getTool(TOOL_NAME));
    }

    @Test
    void shouldHandleNullInUnregisterTools() {
        // Act — should not throw
        service.unregisterTools(null);

        // Assert — original tool still present
        assertNotNull(service.getTool(TOOL_NAME));
    }

    @Test
    void shouldHandleEmptyCollectionInUnregisterTools() {
        // Act — should not throw
        service.unregisterTools(Collections.emptyList());

        // Assert — original tool still present
        assertNotNull(service.getTool(TOOL_NAME));
    }

    // ==================== sanitizeToolName ====================

    @Test
    void shouldSanitizeToolNames() {
        // Arrange — register a tool called "shell"
        ToolComponent shellTool = mock(ToolComponent.class);
        when(shellTool.getToolName()).thenReturn("shell");
        when(shellTool.isEnabled()).thenReturn(true);
        ToolResult successResult = ToolResult.success("done");
        when(shellTool.execute(any())).thenReturn(CompletableFuture.completedFuture(successResult));
        service.registerTool(shellTool);

        AgentContext context = buildContext();
        // Tool call name with special tokens like "<|channel|>"
        Message.ToolCall toolCall = buildToolCall("shell<|channel|>", Map.of());

        // Act
        ToolCallExecutionResult result = service.execute(context, toolCall);

        // Assert — sanitized to "shell", tool found and executed
        assertNotNull(result);
        assertTrue(result.toolResult().isSuccess());
        assertEquals("done", result.toolMessageContent());
    }

    @Test
    void shouldSanitizeToolNameWithSpecialCharacters() {
        // Arrange
        AgentContext context = buildContext();
        // Name with dots/special chars that get stripped, resulting in mismatch
        Message.ToolCall toolCall = buildToolCall("test_tool.extra", Map.of());
        ToolResult successResult = ToolResult.success("ok");
        when(toolComponent.execute(any())).thenReturn(CompletableFuture.completedFuture(successResult));

        // Act
        ToolCallExecutionResult result = service.execute(context, toolCall);

        // Assert — "test_tool.extra" -> sanitized to "test_tool", matches registered
        // tool
        assertNotNull(result);
        assertTrue(result.toolResult().isSuccess());
    }

    // ==================== extractAttachment: screenshot base64
    // ====================

    @Test
    void shouldExtractImageAttachment() {
        // Arrange
        AgentContext context = buildContext();
        Message.ToolCall toolCall = buildToolCall(TOOL_NAME, Map.of());
        byte[] pngBytes = new byte[] { (byte) 0x89, 0x50, 0x4E, 0x47 };
        String base64 = Base64.getEncoder().encodeToString(pngBytes);
        Map<String, Object> data = new HashMap<>();
        data.put("screenshot_base64", base64);
        ToolResult successResult = ToolResult.builder()
                .success(true)
                .output("Screenshot captured")
                .data(data)
                .build();
        when(toolComponent.execute(any())).thenReturn(CompletableFuture.completedFuture(successResult));

        // Act
        ToolCallExecutionResult result = service.execute(context, toolCall);

        // Assert
        assertNotNull(result.extractedAttachment());
        Attachment attachment = result.extractedAttachment();
        assertEquals(Attachment.Type.IMAGE, attachment.getType());
        assertEquals("screenshot.png", attachment.getFilename());
        assertEquals("image/png", attachment.getMimeType());
        assertEquals(pngBytes.length, attachment.getData().length);
    }

    @Test
    void shouldExtractDirectAttachmentObject() {
        // Arrange
        AgentContext context = buildContext();
        Message.ToolCall toolCall = buildToolCall(TOOL_NAME, Map.of());
        Attachment directAttachment = Attachment.builder()
                .type(Attachment.Type.DOCUMENT)
                .data(new byte[] { 1, 2, 3 })
                .filename("report.pdf")
                .mimeType("application/pdf")
                .build();
        Map<String, Object> data = new HashMap<>();
        data.put("attachment", directAttachment);
        ToolResult successResult = ToolResult.builder()
                .success(true)
                .output("Document generated")
                .data(data)
                .build();
        when(toolComponent.execute(any())).thenReturn(CompletableFuture.completedFuture(successResult));

        // Act
        ToolCallExecutionResult result = service.execute(context, toolCall);

        // Assert
        assertNotNull(result.extractedAttachment());
        assertEquals(Attachment.Type.DOCUMENT, result.extractedAttachment().getType());
        assertEquals("report.pdf", result.extractedAttachment().getFilename());
    }

    @Test
    void shouldExtractFileBytesAttachment() {
        // Arrange
        AgentContext context = buildContext();
        Message.ToolCall toolCall = buildToolCall(TOOL_NAME, Map.of());
        byte[] fileData = new byte[] { 10, 20, 30 };
        Map<String, Object> data = new HashMap<>();
        data.put("file_bytes", fileData);
        data.put("filename", "data.csv");
        data.put("mime_type", "text/csv");
        ToolResult successResult = ToolResult.builder()
                .success(true)
                .output("File ready")
                .data(data)
                .build();
        when(toolComponent.execute(any())).thenReturn(CompletableFuture.completedFuture(successResult));

        // Act
        ToolCallExecutionResult result = service.execute(context, toolCall);

        // Assert
        assertNotNull(result.extractedAttachment());
        assertEquals(Attachment.Type.DOCUMENT, result.extractedAttachment().getType());
        assertEquals("data.csv", result.extractedAttachment().getFilename());
        assertEquals("text/csv", result.extractedAttachment().getMimeType());
    }

    @Test
    void shouldExtractFileBytesAsImageWhenMimeTypeIsImage() {
        // Arrange
        AgentContext context = buildContext();
        Message.ToolCall toolCall = buildToolCall(TOOL_NAME, Map.of());
        byte[] imageData = new byte[] { 1, 2, 3, 4 };
        Map<String, Object> data = new HashMap<>();
        data.put("file_bytes", imageData);
        data.put("filename", "chart.png");
        data.put("mime_type", "image/png");
        ToolResult successResult = ToolResult.builder()
                .success(true)
                .output("Chart generated")
                .data(data)
                .build();
        when(toolComponent.execute(any())).thenReturn(CompletableFuture.completedFuture(successResult));

        // Act
        ToolCallExecutionResult result = service.execute(context, toolCall);

        // Assert
        assertNotNull(result.extractedAttachment());
        assertEquals(Attachment.Type.IMAGE, result.extractedAttachment().getType());
    }

    // ==================== extractAttachment: invalid base64 ====================

    @Test
    void shouldHandleInvalidBase64InAttachment() {
        // Arrange
        AgentContext context = buildContext();
        Message.ToolCall toolCall = buildToolCall(TOOL_NAME, Map.of());
        Map<String, Object> data = new HashMap<>();
        data.put("screenshot_base64", "!!!not-valid-base64!!!");
        ToolResult successResult = ToolResult.builder()
                .success(true)
                .output("Screenshot captured")
                .data(data)
                .build();
        when(toolComponent.execute(any())).thenReturn(CompletableFuture.completedFuture(successResult));

        // Act
        ToolCallExecutionResult result = service.execute(context, toolCall);

        // Assert — should not crash, attachment should be null
        assertNotNull(result);
        assertNull(result.extractedAttachment());
        assertEquals("Screenshot captured", result.toolMessageContent());
    }

    @Test
    void shouldReturnNullAttachmentWhenResultIsFailure() {
        // Arrange
        AgentContext context = buildContext();
        ToolResult failureResult = ToolResult.failure("error");

        // Act
        Attachment attachment = service.extractAttachment(context, failureResult, TOOL_NAME);

        // Assert
        assertNull(attachment);
    }

    @Test
    void shouldReturnNullAttachmentWhenResultIsNull() {
        // Arrange
        AgentContext context = buildContext();

        // Act
        Attachment attachment = service.extractAttachment(context, null, TOOL_NAME);

        // Assert
        assertNull(attachment);
    }

    @Test
    void shouldReturnNullAttachmentWhenDataIsNotMap() {
        // Arrange
        AgentContext context = buildContext();
        ToolResult successResult = ToolResult.builder()
                .success(true)
                .output("ok")
                .data("not a map")
                .build();

        // Act
        Attachment attachment = service.extractAttachment(context, successResult, TOOL_NAME);

        // Assert
        assertNull(attachment);
    }

    // ==================== confirmation ====================

    @Test
    void shouldRequestConfirmation() {
        // Arrange
        AgentContext context = buildContext();
        Message.ToolCall toolCall = buildToolCall(TOOL_NAME, Map.of());
        when(confirmationPolicy.requiresConfirmation(any())).thenReturn(true);
        when(confirmationPort.isAvailable()).thenReturn(true);
        when(confirmationPort.requestConfirmation(eq(CHAT_ID), eq(TOOL_NAME), anyString()))
                .thenReturn(CompletableFuture.completedFuture(false));
        when(confirmationPolicy.describeAction(any())).thenReturn("Test action");

        // Act
        ToolCallExecutionResult result = service.execute(context, toolCall);

        // Assert — denied
        assertNotNull(result);
        ToolResult toolResult = result.toolResult();
        assertTrue(!toolResult.isSuccess());
        assertEquals(ToolFailureKind.CONFIRMATION_DENIED, toolResult.getFailureKind());
        assertTrue(result.toolMessageContent().contains("Cancelled by user"));
        verify(confirmationPort).requestConfirmation(eq(CHAT_ID), eq(TOOL_NAME), eq("Test action"));
    }

    @Test
    void shouldProceedWhenConfirmationApproved() {
        // Arrange
        AgentContext context = buildContext();
        Message.ToolCall toolCall = buildToolCall(TOOL_NAME, Map.of());
        when(confirmationPolicy.requiresConfirmation(any())).thenReturn(true);
        when(confirmationPort.isAvailable()).thenReturn(true);
        when(confirmationPort.requestConfirmation(eq(CHAT_ID), eq(TOOL_NAME), anyString()))
                .thenReturn(CompletableFuture.completedFuture(true));
        when(confirmationPolicy.describeAction(any())).thenReturn("Approved action");
        ToolResult successResult = ToolResult.success("Executed after approval");
        when(toolComponent.execute(any())).thenReturn(CompletableFuture.completedFuture(successResult));

        // Act
        ToolCallExecutionResult result = service.execute(context, toolCall);

        // Assert — approved, tool executed
        assertNotNull(result);
        assertTrue(result.toolResult().isSuccess());
        assertEquals("Executed after approval", result.toolMessageContent());
    }

    @Test
    void shouldSkipConfirmationWhenPortNotAvailable() {
        // Arrange
        AgentContext context = buildContext();
        Message.ToolCall toolCall = buildToolCall(TOOL_NAME, Map.of());
        when(confirmationPolicy.requiresConfirmation(any())).thenReturn(true);
        when(confirmationPort.isAvailable()).thenReturn(false);
        ToolResult successResult = ToolResult.success("Executed without confirmation");
        when(toolComponent.execute(any())).thenReturn(CompletableFuture.completedFuture(successResult));

        // Act
        ToolCallExecutionResult result = service.execute(context, toolCall);

        // Assert — no confirmation requested, tool executed directly
        assertNotNull(result);
        assertTrue(result.toolResult().isSuccess());
        verify(confirmationPort, never()).requestConfirmation(anyString(), anyString(), anyString());
    }

    @Test
    void shouldDenyWhenConfirmationRequestFails() {
        // Arrange
        AgentContext context = buildContext();
        Message.ToolCall toolCall = buildToolCall(TOOL_NAME, Map.of());
        when(confirmationPolicy.requiresConfirmation(any())).thenReturn(true);
        when(confirmationPort.isAvailable()).thenReturn(true);
        when(confirmationPort.requestConfirmation(eq(CHAT_ID), eq(TOOL_NAME), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("Timeout")));
        when(confirmationPolicy.describeAction(any())).thenReturn("Failed action");

        // Act
        ToolCallExecutionResult result = service.execute(context, toolCall);

        // Assert — exception during confirmation results in denial
        assertNotNull(result);
        ToolResult toolResult = result.toolResult();
        assertTrue(!toolResult.isSuccess());
        assertEquals(ToolFailureKind.CONFIRMATION_DENIED, toolResult.getFailureKind());
    }

    // ==================== notification ====================

    @Test
    void shouldNotifyToolExecution() {
        // Arrange
        AgentContext context = buildContext();
        Message.ToolCall toolCall = buildToolCall(TOOL_NAME, Map.of());
        when(confirmationPolicy.isEnabled()).thenReturn(false);
        when(confirmationPolicy.isNotableAction(any())).thenReturn(true);
        when(confirmationPolicy.describeAction(any())).thenReturn("Run command: ls -la");
        when(channelPort.sendMessage(anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        ToolResult successResult = ToolResult.success("ok");
        when(toolComponent.execute(any())).thenReturn(CompletableFuture.completedFuture(successResult));

        // Act
        ToolCallExecutionResult result = service.execute(context, toolCall);

        // Assert
        assertNotNull(result);
        assertTrue(result.toolResult().isSuccess());
        verify(channelPort).sendMessage(eq(CHAT_ID), anyString());
    }

    @Test
    void shouldNotNotifyWhenConfirmationPolicyEnabled() {
        // Arrange
        AgentContext context = buildContext();
        Message.ToolCall toolCall = buildToolCall(TOOL_NAME, Map.of());
        when(confirmationPolicy.isEnabled()).thenReturn(true);
        when(confirmationPolicy.isNotableAction(any())).thenReturn(true);
        ToolResult successResult = ToolResult.success("ok");
        when(toolComponent.execute(any())).thenReturn(CompletableFuture.completedFuture(successResult));

        // Act
        service.execute(context, toolCall);

        // Assert — notification not sent because confirmation policy is enabled
        verify(channelPort, never()).sendMessage(anyString(), anyString());
    }

    @Test
    void shouldNotNotifyWhenActionNotNotable() {
        // Arrange
        AgentContext context = buildContext();
        Message.ToolCall toolCall = buildToolCall(TOOL_NAME, Map.of());
        when(confirmationPolicy.isEnabled()).thenReturn(false);
        when(confirmationPolicy.isNotableAction(any())).thenReturn(false);
        ToolResult successResult = ToolResult.success("ok");
        when(toolComponent.execute(any())).thenReturn(CompletableFuture.completedFuture(successResult));

        // Act
        service.execute(context, toolCall);

        // Assert — notification not sent because action is not notable
        verify(channelPort, never()).sendMessage(anyString(), anyString());
    }

    @Test
    void shouldNotCrashWhenNotificationFails() {
        // Arrange
        AgentContext context = buildContext();
        Message.ToolCall toolCall = buildToolCall(TOOL_NAME, Map.of());
        when(confirmationPolicy.isEnabled()).thenReturn(false);
        when(confirmationPolicy.isNotableAction(any())).thenReturn(true);
        when(confirmationPolicy.describeAction(any())).thenReturn("action");
        when(channelPort.sendMessage(anyString(), anyString()))
                .thenThrow(new RuntimeException("Channel error"));
        ToolResult successResult = ToolResult.success("ok");
        when(toolComponent.execute(any())).thenReturn(CompletableFuture.completedFuture(successResult));

        // Act
        ToolCallExecutionResult result = service.execute(context, toolCall);

        // Assert — tool execution still succeeds despite notification failure
        assertNotNull(result);
        assertTrue(result.toolResult().isSuccess());
    }

    // ==================== truncateToolResult edge cases ====================

    @Test
    void shouldReturnNullWhenTruncatingNullContent() {
        // Act
        String result = service.truncateToolResult(null, TOOL_NAME);

        // Assert
        assertNull(result);
    }

    @Test
    void shouldNotTruncateWhenMaxCharsDisabled() {
        // Arrange
        properties.getAutoCompact().setMaxToolResultChars(0);
        String content = "a".repeat(10000);

        // Act
        String result = service.truncateToolResult(content, TOOL_NAME);

        // Assert
        assertEquals(content, result);
    }

    // ==================== tool execution exception handling ====================

    @Test
    void shouldHandleToolExecutionException() {
        // Arrange
        AgentContext context = buildContext();
        Message.ToolCall toolCall = buildToolCall(TOOL_NAME, Map.of());
        when(toolComponent.execute(any())).thenReturn(
                CompletableFuture.failedFuture(new RuntimeException("Execution boom")));

        // Act
        ToolCallExecutionResult result = service.execute(context, toolCall);

        // Assert
        assertNotNull(result);
        ToolResult toolResult = result.toolResult();
        assertTrue(!toolResult.isSuccess());
        assertEquals(ToolFailureKind.EXECUTION_FAILED, toolResult.getFailureKind());
        assertTrue(toolResult.getError().contains("Tool execution failed"));
    }

    // ==================== AgentContextHolder lifecycle ====================

    @Test
    void shouldClearAgentContextHolderAfterExecution() {
        // Arrange
        AgentContext context = buildContext();
        Message.ToolCall toolCall = buildToolCall(TOOL_NAME, Map.of());
        ToolResult successResult = ToolResult.success("ok");
        when(toolComponent.execute(any())).thenReturn(CompletableFuture.completedFuture(successResult));

        // Act
        service.execute(context, toolCall);

        // Assert — AgentContextHolder should be cleared after execution
        assertNull(AgentContextHolder.get());
    }

    @Test
    void shouldClearAgentContextHolderEvenOnException() {
        // Arrange
        AgentContext context = buildContext();
        Message.ToolCall toolCall = buildToolCall(TOOL_NAME, Map.of());
        when(toolComponent.execute(any())).thenThrow(new RuntimeException("Unexpected"));

        // Act
        ToolCallExecutionResult result = service.execute(context, toolCall);

        // Assert — AgentContextHolder should still be cleared
        assertNull(AgentContextHolder.get());
        assertNotNull(result);
    }

    // ==================== buildToolMessageContent: failure with output
    // ====================

    @Test
    void shouldPreferOutputOverErrorInFailureResult() {
        // Arrange
        AgentContext context = buildContext();
        Message.ToolCall toolCall = buildToolCall(TOOL_NAME, Map.of());
        ToolResult partialResult = ToolResult.builder()
                .success(false)
                .output("Partial output before failure")
                .error("Something went wrong")
                .build();
        when(toolComponent.execute(any())).thenReturn(CompletableFuture.completedFuture(partialResult));

        // Act
        ToolCallExecutionResult result = service.execute(context, toolCall);

        // Assert — when failure has non-blank output, output is preferred
        assertEquals("Partial output before failure", result.toolMessageContent());
    }

    // ==================== helper methods ====================

    private AgentContext buildContext() {
        AgentSession session = AgentSession.builder()
                .id(CHANNEL_TYPE + ":" + CHAT_ID)
                .channelType(CHANNEL_TYPE)
                .chatId(CHAT_ID)
                .messages(new ArrayList<>())
                .build();
        return AgentContext.builder()
                .session(session)
                .messages(new ArrayList<>())
                .build();
    }

    private Message.ToolCall buildToolCall(String name, Map<String, Object> arguments) {
        return Message.ToolCall.builder()
                .id(TOOL_CALL_ID)
                .name(name)
                .arguments(arguments)
                .build();
    }
}
