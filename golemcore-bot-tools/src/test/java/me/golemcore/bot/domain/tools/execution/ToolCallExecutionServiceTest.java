package me.golemcore.bot.domain.tools.execution;

import me.golemcore.bot.domain.component.ToolComponent;
import me.golemcore.bot.domain.loop.AgentContextHolder;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.Attachment;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.ToolArtifact;
import me.golemcore.bot.domain.model.ToolFailureKind;
import me.golemcore.bot.domain.model.ToolResult;
import me.golemcore.bot.domain.tools.artifacts.ToolArtifactPersister;
import me.golemcore.bot.domain.tools.artifacts.ToolArtifactService;
import me.golemcore.bot.domain.tools.registry.ToolRegistryService;
import me.golemcore.bot.port.outbound.ConfirmationPort;
import me.golemcore.bot.port.outbound.ToolRuntimeSettingsPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    private ToolRuntimeSettingsPort settingsPort;
    private ToolArtifactService toolArtifactService;
    private ToolRegistryService toolRegistryService;
    private ToolAttachmentExtractor attachmentExtractor;
    private ToolResultPostProcessor resultPostProcessor;
    private ToolCallExecutionService service;
    private int maxToolResultChars;

    @BeforeEach
    void setUp() {
        toolComponent = mock(ToolComponent.class);
        confirmationPolicy = mock(ToolConfirmationPolicy.class);
        confirmationPort = mock(ConfirmationPort.class);
        maxToolResultChars = MAX_TOOL_RESULT_CHARS;
        settingsPort = toolRuntimeSettingsPort();
        toolArtifactService = mock(ToolArtifactService.class);

        when(toolComponent.getToolName()).thenReturn(TOOL_NAME);
        when(toolComponent.isEnabled()).thenReturn(true);
        when(confirmationPort.isAvailable()).thenReturn(false);
        when(confirmationPolicy.requiresConfirmation(any())).thenReturn(false);
        when(confirmationPolicy.isEnabled()).thenReturn(true);
        when(toolArtifactService.saveArtifact(anyString(), anyString(), anyString(), any(), anyString()))
                .thenAnswer(invocation -> {
                    String filename = invocation.getArgument(2, String.class);
                    byte[] bytes = invocation.getArgument(3, byte[].class);
                    String mimeType = invocation.getArgument(4, String.class);
                    String safeFilename = filename != null ? filename : "download.bin";
                    return ToolArtifact.builder().path(".golemcore/tool-artifacts/session/test/" + safeFilename)
                            .filename(safeFilename).mimeType(mimeType != null ? mimeType : "application/octet-stream")
                            .size(bytes != null ? bytes.length : 0L)
                            .downloadUrl("/api/files/download?path=.golemcore%2Ftool-artifacts%2Fsession%2Ftest%2F"
                                    + safeFilename)
                            .build();
                });
        when(toolArtifactService.buildThumbnailBase64(anyString())).thenReturn("thumb-base64");

        toolRegistryService = new ToolRegistryService(List.of(toolComponent));
        attachmentExtractor = new ToolAttachmentExtractor();
        ToolArtifactPersister artifactPersister = new ToolArtifactPersister(toolArtifactService);
        resultPostProcessor = new ToolResultPostProcessor(settingsPort);
        service = new ToolCallExecutionService(toolRegistryService, confirmationPolicy, confirmationPort,
                attachmentExtractor, artifactPersister, resultPostProcessor);
    }

    private ToolRuntimeSettingsPort toolRuntimeSettingsPort() {
        return new ToolRuntimeSettingsPort() {
            @Override
            public ToolExecutionSettings toolExecution() {
                return new ToolExecutionSettings(maxToolResultChars);
            }

            @Override
            public TurnSettings turn() {
                return ToolRuntimeSettingsPort.defaultTurnSettings();
            }

            @Override
            public ToolLoopSettings toolLoop() {
                return ToolRuntimeSettingsPort.defaultToolLoopSettings();
            }
        };
    }

    @AfterEach
    void tearDown() {
        AgentContextHolder.clear();
    }

    // ==================== execute: happy path ====================

    @Test
    void shouldExecuteToolCallSuccessfully() {
        AgentContext context = buildContext();
        Message.ToolCall toolCall = buildToolCall(TOOL_NAME, Map.of("key", "value"));
        ToolResult expectedResult = ToolResult.success("Tool executed OK");
        when(toolComponent.execute(any())).thenReturn(CompletableFuture.completedFuture(expectedResult));

        ToolCallExecutionResult result = service.execute(context, toolCall);

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
        AgentContext context = buildContext();
        Message.ToolCall toolCall = buildToolCall("nonexistent_tool", Map.of());

        ToolCallExecutionResult result = service.execute(context, toolCall);

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
        AgentContext context = buildContext();
        Message.ToolCall toolCall = buildToolCall(TOOL_NAME, Map.of());
        when(toolComponent.isEnabled()).thenReturn(false);

        ToolCallExecutionResult result = service.execute(context, toolCall);

        assertNotNull(result);
        ToolResult toolResult = result.toolResult();
        assertTrue(!toolResult.isSuccess());
        assertEquals(ToolFailureKind.POLICY_DENIED, toolResult.getFailureKind());
        assertTrue(toolResult.getError().contains("Tool is disabled"));
    }

    // ==================== truncation ====================

    @Test
    void shouldTruncateLongToolResults() {
        AgentContext context = buildContext();
        Message.ToolCall toolCall = buildToolCall(TOOL_NAME, Map.of());
        String longOutput = "x".repeat(MAX_TOOL_RESULT_CHARS + 1000);
        ToolResult successResult = ToolResult.success(longOutput);
        when(toolComponent.execute(any())).thenReturn(CompletableFuture.completedFuture(successResult));

        ToolCallExecutionResult result = service.execute(context, toolCall);

        assertNotNull(result);
        assertTrue(result.toolMessageContent().length() <= MAX_TOOL_RESULT_CHARS + 10,
                "Truncated content should be around maxToolResultChars");
        assertTrue(result.toolMessageContent().contains("[OUTPUT TRUNCATED:"));
    }

    @Test
    void shouldPreserveShortToolResults() {
        AgentContext context = buildContext();
        Message.ToolCall toolCall = buildToolCall(TOOL_NAME, Map.of());
        String shortOutput = "short result";
        ToolResult successResult = ToolResult.success(shortOutput);
        when(toolComponent.execute(any())).thenReturn(CompletableFuture.completedFuture(successResult));

        ToolCallExecutionResult result = service.execute(context, toolCall);

        assertNotNull(result);
        assertEquals("short result", result.toolMessageContent());
    }

    // ==================== buildToolMessageContent ====================

    @Test
    void shouldBuildSuccessMessageContent() {
        AgentContext context = buildContext();
        Message.ToolCall toolCall = buildToolCall(TOOL_NAME, Map.of());
        ToolResult successResult = ToolResult.success("Success output text");
        when(toolComponent.execute(any())).thenReturn(CompletableFuture.completedFuture(successResult));

        ToolCallExecutionResult result = service.execute(context, toolCall);

        assertEquals("Success output text", result.toolMessageContent());
    }

    @Test
    void shouldBuildErrorMessageContent() {
        AgentContext context = buildContext();
        Message.ToolCall toolCall = buildToolCall(TOOL_NAME, Map.of());
        ToolResult failureResult = ToolResult.failure("Something went wrong");
        when(toolComponent.execute(any())).thenReturn(CompletableFuture.completedFuture(failureResult));

        ToolCallExecutionResult result = service.execute(context, toolCall);

        assertTrue(result.toolMessageContent().contains("Error: Something went wrong"));
    }

    // ==================== sanitizeToolName ====================

    @Test
    void shouldSanitizeToolNames() {
        ToolComponent shellTool = mock(ToolComponent.class);
        when(shellTool.getToolName()).thenReturn("shell");
        when(shellTool.isEnabled()).thenReturn(true);
        ToolResult successResult = ToolResult.success("done");
        when(shellTool.execute(any())).thenReturn(CompletableFuture.completedFuture(successResult));
        toolRegistryService.registerTool(shellTool);

        AgentContext context = buildContext();
        Message.ToolCall toolCall = buildToolCall("shell<|channel|>", Map.of());

        ToolCallExecutionResult result = service.execute(context, toolCall);

        assertNotNull(result);
        assertTrue(result.toolResult().isSuccess());
        assertEquals("done", result.toolMessageContent());
    }

    @Test
    void shouldSanitizeToolNameWithSpecialCharacters() {
        AgentContext context = buildContext();
        Message.ToolCall toolCall = buildToolCall("test_tool.extra", Map.of());
        ToolResult successResult = ToolResult.success("ok");
        when(toolComponent.execute(any())).thenReturn(CompletableFuture.completedFuture(successResult));

        ToolCallExecutionResult result = service.execute(context, toolCall);

        assertNotNull(result);
        assertTrue(result.toolResult().isSuccess());
    }

    @Test
    void shouldExecuteContextScopedToolWithoutGlobalRegistration() {
        ToolComponent scopedTool = mock(ToolComponent.class);
        when(scopedTool.getToolName()).thenReturn("scoped_tool");
        when(scopedTool.isEnabled()).thenReturn(true);
        when(scopedTool.execute(any())).thenReturn(CompletableFuture.completedFuture(ToolResult.success("scoped ok")));

        AgentContext context = buildContext();
        context.setAttribute(ContextAttributes.CONTEXT_SCOPED_TOOLS, Map.of("scoped_tool", scopedTool));

        ToolCallExecutionResult result = service.execute(context, buildToolCall("scoped_tool", Map.of("q", "1")));

        assertNotNull(result);
        assertTrue(result.toolResult().isSuccess());
        assertEquals("scoped ok", result.toolMessageContent());
        verify(scopedTool).execute(Map.of("q", "1"));
    }

    @Test
    void shouldPreferContextScopedToolOverGlobalToolWithSameName() {
        ToolComponent scopedTool = mock(ToolComponent.class);
        when(scopedTool.getToolName()).thenReturn(TOOL_NAME);
        when(scopedTool.isEnabled()).thenReturn(true);
        when(scopedTool.execute(any())).thenReturn(CompletableFuture.completedFuture(ToolResult.success("scoped ok")));
        when(toolComponent.execute(any()))
                .thenReturn(CompletableFuture.completedFuture(ToolResult.success("global ok")));

        AgentContext context = buildContext();
        context.setAttribute(ContextAttributes.CONTEXT_SCOPED_TOOLS, Map.of(TOOL_NAME, scopedTool));

        ToolCallExecutionResult result = service.execute(context, buildToolCall(TOOL_NAME, Map.of("q", "1")));

        assertNotNull(result);
        assertTrue(result.toolResult().isSuccess());
        assertEquals("scoped ok", result.toolMessageContent());
        verify(scopedTool).execute(Map.of("q", "1"));
        verify(toolComponent, never()).execute(any());
    }

    // ==================== extractAttachment: screenshot base64
    // ====================

    @Test
    void shouldExtractImageAttachment() {
        AgentContext context = buildContext();
        Message.ToolCall toolCall = buildToolCall(TOOL_NAME, Map.of());
        byte[] pngBytes = new byte[] { (byte) 0x89, 0x50, 0x4E, 0x47 };
        String base64 = Base64.getEncoder().encodeToString(pngBytes);
        Map<String, Object> data = new HashMap<>();
        data.put("screenshot_base64", base64);
        ToolResult successResult = ToolResult.builder().success(true).output("Screenshot captured").data(data).build();
        when(toolComponent.execute(any())).thenReturn(CompletableFuture.completedFuture(successResult));

        ToolCallExecutionResult result = service.execute(context, toolCall);

        assertNotNull(result.extractedAttachment());
        Attachment attachment = result.extractedAttachment();
        assertEquals(Attachment.Type.IMAGE, attachment.getType());
        assertEquals("screenshot.png", attachment.getFilename());
        assertEquals("image/png", attachment.getMimeType());
        assertEquals(pngBytes.length, attachment.getData().length);
        assertEquals("thumb-base64", attachment.getThumbnailBase64());
        Map<?, ?> sanitizedData = (Map<?, ?>) result.toolResult().getData();
        assertEquals("thumb-base64", sanitizedData.get("internal_file_thumbnail_base64"));
    }

    @Test
    void shouldExtractDirectAttachmentObject() {
        AgentContext context = buildContext();
        Message.ToolCall toolCall = buildToolCall(TOOL_NAME, Map.of());
        Attachment directAttachment = Attachment.builder().type(Attachment.Type.DOCUMENT).data(new byte[] { 1, 2, 3 })
                .filename("report.pdf").mimeType("application/pdf").build();
        Map<String, Object> data = new HashMap<>();
        data.put("attachment", directAttachment);
        ToolResult successResult = ToolResult.builder().success(true).output("Document generated").data(data).build();
        when(toolComponent.execute(any())).thenReturn(CompletableFuture.completedFuture(successResult));

        ToolCallExecutionResult result = service.execute(context, toolCall);

        assertNotNull(result.extractedAttachment());
        assertEquals(Attachment.Type.DOCUMENT, result.extractedAttachment().getType());
        assertEquals("report.pdf", result.extractedAttachment().getFilename());
        assertTrue(result.toolMessageContent().contains("Internal file: [report.pdf]("));
        assertTrue(result.toolMessageContent()
                .contains("Workspace path: `.golemcore/tool-artifacts/session/test/report.pdf`"));
        Map<?, ?> sanitizedData = (Map<?, ?>) result.toolResult().getData();
        assertTrue(!sanitizedData.containsKey("attachment"));
        assertEquals("/api/files/download?path=.golemcore%2Ftool-artifacts%2Fsession%2Ftest%2Freport.pdf",
                sanitizedData.get("internal_file_url"));
        assertNull(sanitizedData.get("internal_file_thumbnail_base64"));
    }

    @Test
    void shouldExtractFileBytesAttachment() {
        AgentContext context = buildContext();
        Message.ToolCall toolCall = buildToolCall(TOOL_NAME, Map.of());
        byte[] fileData = new byte[] { 10, 20, 30 };
        Map<String, Object> data = new HashMap<>();
        data.put("file_bytes", fileData);
        data.put("filename", "data.csv");
        data.put("mime_type", "text/csv");
        ToolResult successResult = ToolResult.builder().success(true).output("File ready").data(data).build();
        when(toolComponent.execute(any())).thenReturn(CompletableFuture.completedFuture(successResult));

        ToolCallExecutionResult result = service.execute(context, toolCall);

        assertNotNull(result.extractedAttachment());
        assertEquals(Attachment.Type.DOCUMENT, result.extractedAttachment().getType());
        assertEquals("data.csv", result.extractedAttachment().getFilename());
        assertEquals("text/csv", result.extractedAttachment().getMimeType());
        Map<?, ?> sanitizedData = (Map<?, ?>) result.toolResult().getData();
        assertTrue(!sanitizedData.containsKey("file_bytes"));
        assertEquals("data.csv", sanitizedData.get("internal_file_name"));
    }

    @Test
    void shouldExtractFileBytesAsImageWhenMimeTypeIsImage() {
        AgentContext context = buildContext();
        Message.ToolCall toolCall = buildToolCall(TOOL_NAME, Map.of());
        byte[] imageData = new byte[] { 1, 2, 3, 4 };
        Map<String, Object> data = new HashMap<>();
        data.put("file_bytes", imageData);
        data.put("filename", "chart.png");
        data.put("mime_type", "image/png");
        ToolResult successResult = ToolResult.builder().success(true).output("Chart generated").data(data).build();
        when(toolComponent.execute(any())).thenReturn(CompletableFuture.completedFuture(successResult));

        ToolCallExecutionResult result = service.execute(context, toolCall);

        assertNotNull(result.extractedAttachment());
        assertEquals(Attachment.Type.IMAGE, result.extractedAttachment().getType());
        assertEquals("thumb-base64", result.extractedAttachment().getThumbnailBase64());
        Map<?, ?> sanitizedData = (Map<?, ?>) result.toolResult().getData();
        assertEquals("thumb-base64", sanitizedData.get("internal_file_thumbnail_base64"));
    }

    // ==================== extractAttachment: invalid base64 ====================

    @Test
    void shouldHandleInvalidBase64InAttachment() {
        AgentContext context = buildContext();
        Message.ToolCall toolCall = buildToolCall(TOOL_NAME, Map.of());
        Map<String, Object> data = new HashMap<>();
        data.put("screenshot_base64", "!!!not-valid-base64!!!");
        ToolResult successResult = ToolResult.builder().success(true).output("Screenshot captured").data(data).build();
        when(toolComponent.execute(any())).thenReturn(CompletableFuture.completedFuture(successResult));

        ToolCallExecutionResult result = service.execute(context, toolCall);

        assertNotNull(result);
        assertNull(result.extractedAttachment());
        assertEquals("Screenshot captured", result.toolMessageContent());
    }

    @Test
    void shouldPreserveToolResultWhenArtifactPersistenceFails() {
        AgentContext context = buildContext();
        Message.ToolCall toolCall = buildToolCall(TOOL_NAME, Map.of());
        Attachment directAttachment = Attachment.builder().type(Attachment.Type.DOCUMENT).data(new byte[] { 1, 2, 3 })
                .filename("report.pdf").mimeType("application/pdf").build();
        Map<String, Object> data = new HashMap<>();
        data.put("attachment", directAttachment);
        ToolResult successResult = ToolResult.builder().success(true).output("Document generated").data(data).build();
        when(toolComponent.execute(any())).thenReturn(CompletableFuture.completedFuture(successResult));
        when(toolArtifactService.saveArtifact(anyString(), anyString(), anyString(), any(), anyString()))
                .thenThrow(new IllegalStateException("disk full"));

        ToolCallExecutionResult result = service.execute(context, toolCall);

        assertNotNull(result.extractedAttachment());
        assertEquals("Document generated", result.toolMessageContent());
        Map<?, ?> sanitizedData = (Map<?, ?>) result.toolResult().getData();
        assertTrue(!sanitizedData.containsKey("attachment"));
        assertTrue(!sanitizedData.containsKey("internal_file_url"));
    }

    @Test
    void shouldReturnNullAttachmentWhenResultIsFailure() {
        AgentContext context = buildContext();
        ToolResult failureResult = ToolResult.failure("error");

        Attachment attachment = attachmentExtractor.extract(failureResult, TOOL_NAME);

        assertNull(attachment);
    }

    @Test
    void shouldReturnNullAttachmentWhenResultIsNull() {
        AgentContext context = buildContext();

        Attachment attachment = attachmentExtractor.extract(null, TOOL_NAME);

        assertNull(attachment);
    }

    @Test
    void shouldReturnNullAttachmentWhenDataIsNotMap() {
        AgentContext context = buildContext();
        ToolResult successResult = ToolResult.builder().success(true).output("ok").data("not a map").build();

        Attachment attachment = attachmentExtractor.extract(successResult, TOOL_NAME);

        assertNull(attachment);
    }

    // ==================== confirmation ====================

    @Test
    void shouldRequestConfirmation() {
        AgentContext context = buildContext();
        Message.ToolCall toolCall = buildToolCall(TOOL_NAME, Map.of());
        when(confirmationPolicy.requiresConfirmation(any())).thenReturn(true);
        when(confirmationPort.isAvailable()).thenReturn(true);
        when(confirmationPort.requestConfirmation(eq(CHAT_ID), eq(TOOL_NAME), anyString()))
                .thenReturn(CompletableFuture.completedFuture(false));
        when(confirmationPolicy.describeAction(any())).thenReturn("Test action");

        ToolCallExecutionResult result = service.execute(context, toolCall);

        assertNotNull(result);
        ToolResult toolResult = result.toolResult();
        assertTrue(!toolResult.isSuccess());
        assertEquals(ToolFailureKind.CONFIRMATION_DENIED, toolResult.getFailureKind());
        assertTrue(result.toolMessageContent().contains("Cancelled by user"));
        verify(confirmationPort).requestConfirmation(eq(CHAT_ID), eq(TOOL_NAME), eq("Test action"));
    }

    @Test
    void shouldProceedWhenConfirmationApproved() {
        AgentContext context = buildContext();
        Message.ToolCall toolCall = buildToolCall(TOOL_NAME, Map.of());
        when(confirmationPolicy.requiresConfirmation(any())).thenReturn(true);
        when(confirmationPort.isAvailable()).thenReturn(true);
        when(confirmationPort.requestConfirmation(eq(CHAT_ID), eq(TOOL_NAME), anyString()))
                .thenReturn(CompletableFuture.completedFuture(true));
        when(confirmationPolicy.describeAction(any())).thenReturn("Approved action");
        ToolResult successResult = ToolResult.success("Executed after approval");
        when(toolComponent.execute(any())).thenReturn(CompletableFuture.completedFuture(successResult));

        ToolCallExecutionResult result = service.execute(context, toolCall);

        assertNotNull(result);
        assertTrue(result.toolResult().isSuccess());
        assertEquals("Executed after approval", result.toolMessageContent());
    }

    @Test
    void shouldSkipConfirmationWhenPortNotAvailable() {
        AgentContext context = buildContext();
        Message.ToolCall toolCall = buildToolCall(TOOL_NAME, Map.of());
        when(confirmationPolicy.requiresConfirmation(any())).thenReturn(true);
        when(confirmationPort.isAvailable()).thenReturn(false);
        ToolResult successResult = ToolResult.success("Executed without confirmation");
        when(toolComponent.execute(any())).thenReturn(CompletableFuture.completedFuture(successResult));

        ToolCallExecutionResult result = service.execute(context, toolCall);

        assertNotNull(result);
        assertTrue(result.toolResult().isSuccess());
        verify(confirmationPort, never()).requestConfirmation(anyString(), anyString(), anyString());
    }

    @Test
    void shouldDenyWhenConfirmationRequestFails() {
        AgentContext context = buildContext();
        Message.ToolCall toolCall = buildToolCall(TOOL_NAME, Map.of());
        when(confirmationPolicy.requiresConfirmation(any())).thenReturn(true);
        when(confirmationPort.isAvailable()).thenReturn(true);
        when(confirmationPort.requestConfirmation(eq(CHAT_ID), eq(TOOL_NAME), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("Timeout")));
        when(confirmationPolicy.describeAction(any())).thenReturn("Failed action");

        ToolCallExecutionResult result = service.execute(context, toolCall);

        assertNotNull(result);
        ToolResult toolResult = result.toolResult();
        assertTrue(!toolResult.isSuccess());
        assertEquals(ToolFailureKind.CONFIRMATION_DENIED, toolResult.getFailureKind());
    }

    // ==================== progress notifications are loop-owned
    // ====================

    @Test
    void shouldNotSendChannelNotificationWhenConfirmationPolicyDisabled() {
        AgentContext context = buildContext();
        Message.ToolCall toolCall = buildToolCall(TOOL_NAME, Map.of());
        when(confirmationPolicy.isEnabled()).thenReturn(false);
        when(confirmationPolicy.isNotableAction(any())).thenReturn(true);
        ToolResult successResult = ToolResult.success("ok");
        when(toolComponent.execute(any())).thenReturn(CompletableFuture.completedFuture(successResult));

        service.execute(context, toolCall);
    }

    @Test
    void shouldExecuteNormallyWithoutToolLevelNotifications() {
        AgentContext context = buildContext();
        Message.ToolCall toolCall = buildToolCall(TOOL_NAME, Map.of());
        when(confirmationPolicy.isEnabled()).thenReturn(false);
        when(confirmationPolicy.isNotableAction(any())).thenReturn(true);
        ToolResult successResult = ToolResult.success("ok");
        when(toolComponent.execute(any())).thenReturn(CompletableFuture.completedFuture(successResult));

        ToolCallExecutionResult result = service.execute(context, toolCall);

        assertNotNull(result);
        assertTrue(result.toolResult().isSuccess());
    }

    // ==================== truncateToolResult edge cases ====================

    @Test
    void shouldReturnNullWhenTruncatingNullContent() {
        String result = resultPostProcessor.truncateToolResult(null, TOOL_NAME);
        assertNull(result);
    }

    @Test
    void shouldNotTruncateWhenMaxCharsDisabled() {
        maxToolResultChars = 0;
        String content = "a".repeat(10000);

        String result = resultPostProcessor.truncateToolResult(content, TOOL_NAME);

        assertEquals(content, result);
    }

    // ==================== tool execution exception handling ====================

    @Test
    void shouldHandleToolExecutionException() {
        AgentContext context = buildContext();
        Message.ToolCall toolCall = buildToolCall(TOOL_NAME, Map.of());
        when(toolComponent.execute(any()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("Execution boom")));

        ToolCallExecutionResult result = service.execute(context, toolCall);

        assertNotNull(result);
        ToolResult toolResult = result.toolResult();
        assertTrue(!toolResult.isSuccess());
        assertEquals(ToolFailureKind.EXECUTION_FAILED, toolResult.getFailureKind());
        assertTrue(toolResult.getError().contains("Tool execution failed"));
    }

    @Test
    void shouldFallbackToExceptionClassWhenRootCauseMessageIsBlank() {
        AgentContext context = buildContext();
        Message.ToolCall toolCall = buildToolCall(TOOL_NAME, Map.of());
        RuntimeException failure = new RuntimeException(new IllegalStateException());
        when(toolComponent.execute(any())).thenReturn(CompletableFuture.failedFuture(failure));

        ToolCallExecutionResult result = service.execute(context, toolCall);

        assertNotNull(result);
        ToolResult toolResult = result.toolResult();
        assertTrue(!toolResult.isSuccess());
        assertEquals(ToolFailureKind.EXECUTION_FAILED, toolResult.getFailureKind());
        assertTrue(toolResult.getError().contains("IllegalStateException"));
        assertTrue(!toolResult.getError().contains("null"));
    }

    // ==================== interrupt propagation ====================

    @Test
    void shouldPreserveInterruptFlagWhenToolGetThrowsInterruptedException() {
        AgentContext context = buildContext();
        Message.ToolCall toolCall = buildToolCall(TOOL_NAME, Map.of());
        CompletableFuture<ToolResult> neverCompletes = new CompletableFuture<>();
        when(toolComponent.execute(any())).thenReturn(neverCompletes);

        Thread.currentThread().interrupt();
        ToolCallExecutionResult result = service.execute(context, toolCall);

        assertTrue(Thread.interrupted(), "Interrupt flag must be preserved after InterruptedException");
        assertNotNull(result);
        assertEquals(ToolFailureKind.EXECUTION_FAILED, result.toolResult().getFailureKind());
        assertTrue(result.toolResult().getError().contains("interrupted"));
    }

    // ==================== AgentContextHolder lifecycle ====================

    @Test
    void shouldExecuteThroughExplicitToolExecutionContext() {
        AgentContext context = buildContext();
        context.setAttribute(ContextAttributes.TRANSPORT_CHAT_ID, "transport-chat");
        Message.ToolCall toolCall = buildToolCall(TOOL_NAME, Map.of());
        ToolResult successResult = ToolResult.success("ok");
        when(toolComponent.execute(any())).thenAnswer(invocation -> {
            assertEquals(context, AgentContextHolder.get());
            return CompletableFuture.completedFuture(successResult);
        });

        ToolExecutionContext executionContext = ToolExecutionContext.from(context);
        ToolCallExecutionResult result = service.execute(executionContext, toolCall);

        assertEquals(CHANNEL_TYPE + ":" + CHAT_ID, executionContext.sessionId());
        assertEquals(CHANNEL_TYPE, executionContext.channelType());
        assertEquals("transport-chat", executionContext.transportChatId());
        assertEquals("ok", result.toolMessageContent());
        assertNull(AgentContextHolder.get());
    }

    @Test
    void shouldSnapshotRuntimeAttributesForToolExecutionContext() {
        AgentContext context = buildContext();
        context.setAttribute("runtime.key", "initial");

        ToolExecutionContext executionContext = ToolExecutionContext.from(context);
        context.setAttribute("runtime.key", "changed");

        assertEquals("initial", executionContext.runtimeAttributes().get("runtime.key"));
        assertThrows(UnsupportedOperationException.class,
                () -> executionContext.runtimeAttributes().put("another.key", "value"));
    }

    @Test
    void shouldClearAgentContextHolderAfterExecution() {
        AgentContext context = buildContext();
        Message.ToolCall toolCall = buildToolCall(TOOL_NAME, Map.of());
        ToolResult successResult = ToolResult.success("ok");
        when(toolComponent.execute(any())).thenReturn(CompletableFuture.completedFuture(successResult));

        service.execute(context, toolCall);

        assertNull(AgentContextHolder.get());
    }

    @Test
    void shouldClearAgentContextHolderEvenOnException() {
        AgentContext context = buildContext();
        Message.ToolCall toolCall = buildToolCall(TOOL_NAME, Map.of());
        when(toolComponent.execute(any())).thenThrow(new RuntimeException("Unexpected"));

        ToolCallExecutionResult result = service.execute(context, toolCall);

        assertNull(AgentContextHolder.get());
        assertNotNull(result);
    }

    // ==================== buildToolMessageContent: failure with output
    // ====================

    @Test
    void shouldPreferOutputOverErrorInFailureResult() {
        AgentContext context = buildContext();
        Message.ToolCall toolCall = buildToolCall(TOOL_NAME, Map.of());
        ToolResult partialResult = ToolResult.builder().success(false).output("Partial output before failure")
                .error("Something went wrong").build();
        when(toolComponent.execute(any())).thenReturn(CompletableFuture.completedFuture(partialResult));

        ToolCallExecutionResult result = service.execute(context, toolCall);

        assertEquals("Partial output before failure", result.toolMessageContent());
    }

    // ==================== helper methods ====================

    private AgentContext buildContext() {
        AgentSession session = AgentSession.builder().id(CHANNEL_TYPE + ":" + CHAT_ID).channelType(CHANNEL_TYPE)
                .chatId(CHAT_ID).messages(new ArrayList<>()).build();
        return AgentContext.builder().session(session).messages(new ArrayList<>()).build();
    }

    private Message.ToolCall buildToolCall(String name, Map<String, Object> arguments) {
        return Message.ToolCall.builder().id(TOOL_CALL_ID).name(name).arguments(arguments).build();
    }
}
