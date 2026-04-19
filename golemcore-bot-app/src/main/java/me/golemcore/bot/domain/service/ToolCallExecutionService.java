package me.golemcore.bot.domain.service;

import lombok.extern.slf4j.Slf4j;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import me.golemcore.bot.domain.component.ToolComponent;
import me.golemcore.bot.domain.loop.AgentContextHolder;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.Attachment;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.ToolArtifact;
import me.golemcore.bot.domain.model.ToolFailureKind;
import me.golemcore.bot.domain.model.ToolResult;
import me.golemcore.bot.port.outbound.ConfirmationPort;
import me.golemcore.bot.port.outbound.ToolRuntimeSettingsPort;
import org.springframework.stereotype.Component;

/**
 * Pure tool-call execution service: executes tools + confirmation gating +
 * truncation + attachment extraction.
 *
 * <p>
 * Does NOT mutate conversation history and does NOT set any loop-control flags.
 */
@Component
@Slf4j
public class ToolCallExecutionService {

    private static final long TOOL_TIMEOUT_SECONDS = 30;
    private static final int MAX_BASE64_LENGTH = 67_000_000;

    private final Map<String, ToolComponent> toolRegistry;
    private final ToolConfirmationPolicy confirmationPolicy;
    private final ConfirmationPort confirmationPort;
    private final ToolRuntimeSettingsPort settingsPort;
    private final ToolArtifactService toolArtifactService;

    public ToolCallExecutionService(List<ToolComponent> toolComponents,
            ToolConfirmationPolicy confirmationPolicy,
            ConfirmationPort confirmationPort,
            ToolRuntimeSettingsPort settingsPort,
            ToolArtifactService toolArtifactService) {
        this.toolRegistry = new ConcurrentHashMap<>();
        for (ToolComponent tool : toolComponents) {
            toolRegistry.put(tool.getToolName(), tool);
        }
        this.confirmationPolicy = confirmationPolicy;
        this.confirmationPort = confirmationPort;
        this.settingsPort = settingsPort;
        this.toolArtifactService = toolArtifactService;
    }

    public ToolCallExecutionResult execute(AgentContext context, Message.ToolCall toolCall) {
        AgentContextHolder.set(context);
        try {
            if (requiresConfirmation(toolCall)) {
                boolean approved = requestConfirmation(context, toolCall);
                if (!approved) {
                    ToolResult denied = ToolResult.failure(ToolFailureKind.CONFIRMATION_DENIED, "Cancelled by user");
                    String content = truncateToolResult("Error: Cancelled by user", toolCall.getName());
                    context.addToolResult(toolCall.getId(), denied);
                    return new ToolCallExecutionResult(toolCall.getId(), toolCall.getName(), denied, content, null);
                }
            }

            ToolResult rawResult = executeToolCall(context, toolCall);
            Attachment attachment = extractAttachment(context, rawResult, toolCall.getName());
            ToolResult result = enrichToolResult(context, rawResult, toolCall.getName(), attachment);
            context.addToolResult(toolCall.getId(), result);

            String content = buildToolMessageContent(result);
            content = truncateToolResult(content, toolCall.getName());
            return new ToolCallExecutionResult(toolCall.getId(), toolCall.getName(), result, content, attachment);
        } finally {
            AgentContextHolder.clear();
        }
    }

    public void registerTool(ToolComponent tool) {
        toolRegistry.put(tool.getToolName(), tool);
    }

    public void unregisterTools(Collection<String> toolNames) {
        if (toolNames == null) {
            return;
        }
        for (String name : toolNames) {
            toolRegistry.remove(name);
        }
        log.debug("Unregistered tools: {}", toolNames);
    }

    public ToolComponent getTool(String name) {
        return toolRegistry.get(name);
    }

    public List<ToolComponent> listTools() {
        return toolRegistry.values().stream()
                .sorted(java.util.Comparator.comparing(ToolComponent::getToolName))
                .toList();
    }

    private boolean requiresConfirmation(Message.ToolCall toolCall) {
        return confirmationPolicy.requiresConfirmation(toolCall) && confirmationPort.isAvailable();
    }

    private boolean requestConfirmation(AgentContext context, Message.ToolCall toolCall) {
        String chatId = SessionIdentitySupport.resolveTransportChatId(context.getSession());
        String description = confirmationPolicy.describeAction(toolCall);

        log.info("[Tools] Requesting confirmation for '{}': {}", toolCall.getName(), description);

        try {
            return confirmationPort.requestConfirmation(chatId, toolCall.getName(), description).join();
        } catch (Exception e) {
            log.error("[Tools] Confirmation request failed, denying", e);
            return false;
        }
    }

    private ToolResult executeToolCall(AgentContext context, Message.ToolCall toolCall) {
        String toolName = sanitizeToolName(toolCall.getName());
        ToolComponent tool = resolveTool(context, toolName);

        if (tool == null) {
            String available = String.join(", ", resolveAvailableToolNames(context));
            return ToolResult.failure(ToolFailureKind.POLICY_DENIED,
                    "Unknown tool: " + toolName + ". Available tools: " + available);
        }

        if (!tool.isEnabled()) {
            return ToolResult.failure(ToolFailureKind.POLICY_DENIED, "Tool is disabled: " + toolCall.getName());
        }

        try {
            CompletableFuture<ToolResult> future = tool.execute(toolCall.getArguments());
            return future.get(TOOL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.info("[Tools] Tool execution interrupted: {}", toolCall.getName());
            return ToolResult.failure(ToolFailureKind.EXECUTION_FAILED,
                    "Tool execution interrupted");
        } catch (Exception e) {
            log.error("Tool execution failed: {}", toolCall.getName(), e);
            return ToolResult.failure(ToolFailureKind.EXECUTION_FAILED,
                    "Tool execution failed: " + safeCauseMessage(e));
        }
    }

    @SuppressWarnings("unchecked")
    private ToolComponent resolveTool(AgentContext context, String toolName) {
        if (context != null) {
            Object scopedTools = context.getAttribute(ContextAttributes.CONTEXT_SCOPED_TOOLS);
            if (scopedTools instanceof Map<?, ?> map) {
                Object candidate = map.get(toolName);
                if (candidate instanceof ToolComponent tool) {
                    return tool;
                }
            }
        }
        return toolRegistry.get(toolName);
    }

    @SuppressWarnings("unchecked")
    private List<String> resolveAvailableToolNames(AgentContext context) {
        Set<String> names = new TreeSet<>(toolRegistry.keySet());
        if (context != null) {
            Object scopedTools = context.getAttribute(ContextAttributes.CONTEXT_SCOPED_TOOLS);
            if (scopedTools instanceof Map<?, ?> map) {
                for (Object key : map.keySet()) {
                    if (key instanceof String name && !name.isBlank()) {
                        names.add(name);
                    }
                }
            }
        }
        return List.copyOf(names);
    }

    private static String safeCauseMessage(Throwable error) {
        if (error == null) {
            return "unknown";
        }

        Throwable cursor = error;
        Throwable cause = cursor.getCause();
        while (cause != null) {
            if (cause.equals(cursor)) {
                break;
            }
            cursor = cause;
            cause = cursor.getCause();
        }

        String message = cursor.getMessage();
        if (message == null || message.isBlank()) {
            message = cursor.getClass().getSimpleName();
        }
        return message;
    }

    /**
     * Strip special tokens and garbage from tool names. Some models (e.g. gpt-oss)
     * leak special tokens like {@code <|channel|>} into tool call names.
     */
    private String sanitizeToolName(String name) {
        if (name == null) {
            return null;
        }
        String sanitized = name.replaceAll("[^a-zA-Z0-9_-].*", "");
        if (!sanitized.equals(name)) {
            log.warn("[Tools] Sanitized tool name: '{}' -> '{}'", name, sanitized);
        }
        return sanitized;
    }

    private String buildToolMessageContent(ToolResult result) {
        if (result == null) {
            return null;
        }
        if (result.isSuccess()) {
            return result.getOutput();
        }
        if (result.getOutput() != null && !result.getOutput().isBlank()) {
            return result.getOutput();
        }
        return "Error: " + result.getError();
    }

    /**
     * Truncate tool result content that exceeds the configured max length.
     */
    public String truncateToolResult(String content, String toolName) {
        if (content == null) {
            return null;
        }
        int maxChars = settingsPort.toolExecution().maxToolResultChars();
        if (maxChars <= 0 || content.length() <= maxChars) {
            return content;
        }

        String suffix = "\n\n[OUTPUT TRUNCATED: " + content.length() + " chars total, showing first "
                + maxChars + " chars. The full result is too large for the context window."
                + " Try a more specific query, use filtering/pagination, or process the data in smaller chunks.]";
        int cutPoint = Math.max(0, maxChars - suffix.length());
        log.warn("[Tools] Truncating '{}' result: {} chars -> ~{} chars",
                toolName, content.length(), cutPoint + suffix.length());
        return content.substring(0, cutPoint) + suffix;
    }

    @SuppressWarnings("unchecked")
    public Attachment extractAttachment(AgentContext context, ToolResult result, String toolName) {
        if (result == null || !result.isSuccess() || !(result.getData() instanceof Map<?, ?> dataMap)) {
            return null;
        }

        Attachment attachment = null;

        Object attachmentObj = dataMap.get("attachment");
        if (attachmentObj instanceof Attachment a) {
            attachment = a;
        }

        if (attachment == null) {
            Object screenshotB64 = dataMap.get("screenshot_base64");
            if (screenshotB64 instanceof String b64) {
                if (b64.length() > MAX_BASE64_LENGTH) {
                    log.warn("[Tools] Base64 data too large ({} chars) from '{}', skipping", b64.length(), toolName);
                } else {
                    try {
                        byte[] bytes = Base64.getDecoder().decode(b64);
                        attachment = Attachment.builder()
                                .type(Attachment.Type.IMAGE)
                                .data(bytes)
                                .filename("screenshot.png")
                                .mimeType("image/png")
                                .build();
                    } catch (IllegalArgumentException e) {
                        log.warn("[Tools] Invalid base64 in screenshot from '{}'", toolName);
                    }
                }
            }
        }

        if (attachment == null) {
            Object fileBytes = dataMap.get("file_bytes");
            if (fileBytes instanceof byte[] bytes) {
                String filename = dataMap.containsKey("filename") ? dataMap.get("filename").toString() : "file";
                String mimeType = dataMap.containsKey("mime_type") ? dataMap.get("mime_type").toString()
                        : "application/octet-stream";
                Attachment.Type type = mimeType.startsWith("image/") ? Attachment.Type.IMAGE : Attachment.Type.DOCUMENT;
                attachment = Attachment.builder()
                        .type(type)
                        .data(bytes)
                        .filename(filename)
                        .mimeType(mimeType)
                        .build();
            }
        }

        return attachment;
    }

    @SuppressWarnings("unchecked")
    private ToolResult enrichToolResult(AgentContext context, ToolResult result, String toolName,
            Attachment attachment) {
        if (result == null) {
            return null;
        }

        Map<String, Object> dataMap = null;
        boolean mutated = false;
        if (result.getData() instanceof Map<?, ?> rawMap) {
            dataMap = new LinkedHashMap<>((Map<String, Object>) rawMap);
            mutated = stripBinaryPayload(dataMap) || mutated;
        }

        ToolArtifact storedFile = null;
        if (attachment != null) {
            try {
                storedFile = toolArtifactService.saveArtifact(
                        resolveSessionId(context),
                        toolName,
                        attachment.getFilename(),
                        attachment.getData(),
                        attachment.getMimeType());
            } catch (RuntimeException ex) {
                log.warn("[Tools] Failed to persist attachment for '{}': {}", toolName, ex.getMessage());
            }
        }

        String output = result.getOutput();
        if (storedFile != null) {
            attachment.setDownloadUrl(storedFile.getDownloadUrl());
            attachment.setInternalFilePath(storedFile.getPath());
            attachment.setFilename(storedFile.getFilename());
            attachment.setMimeType(storedFile.getMimeType());
            if (attachment.getType() == Attachment.Type.IMAGE) {
                attachment.setThumbnailBase64(toolArtifactService.buildThumbnailBase64(storedFile.getPath()));
            }
            if (dataMap == null) {
                dataMap = new LinkedHashMap<>();
            }
            dataMap.put("internal_file_path", storedFile.getPath());
            dataMap.put("internal_file_url", storedFile.getDownloadUrl());
            dataMap.put("internal_file_name", storedFile.getFilename());
            dataMap.put("internal_file_mime_type", storedFile.getMimeType());
            dataMap.put("internal_file_size", storedFile.getSize());
            if (attachment.getType() != null) {
                dataMap.put("internal_file_kind", attachment.getType().name().toLowerCase(Locale.ROOT));
            }
            if (attachment.getThumbnailBase64() != null && !attachment.getThumbnailBase64().isBlank()) {
                dataMap.put("internal_file_thumbnail_base64", attachment.getThumbnailBase64());
            }
            output = appendInternalFileLink(output, storedFile);
            mutated = true;
        }

        if (!mutated) {
            return result;
        }

        return ToolResult.builder()
                .success(result.isSuccess())
                .output(output)
                .data(dataMap)
                .error(result.getError())
                .failureKind(result.getFailureKind())
                .build();
    }

    private boolean stripBinaryPayload(Map<String, Object> dataMap) {
        boolean mutated = false;
        mutated = dataMap.remove("attachment") != null || mutated;
        mutated = dataMap.remove("screenshot_base64") != null || mutated;
        mutated = dataMap.remove("file_bytes") != null || mutated;
        return mutated;
    }

    private String resolveSessionId(AgentContext context) {
        if (context == null || context.getSession() == null || context.getSession().getId() == null
                || context.getSession().getId().isBlank()) {
            return "session";
        }
        return context.getSession().getId();
    }

    private String appendInternalFileLink(String output, ToolArtifact storedFile) {
        String linkBlock = "Internal file: [" + storedFile.getFilename() + "](" + storedFile.getDownloadUrl() + ")\n"
                + "Workspace path: `" + storedFile.getPath() + "`";
        if (output == null || output.isBlank()) {
            return linkBlock;
        }
        if (output.contains(storedFile.getDownloadUrl())) {
            return output;
        }
        return output + "\n\n" + linkBlock;
    }
}
