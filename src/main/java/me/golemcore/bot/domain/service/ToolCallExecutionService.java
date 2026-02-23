package me.golemcore.bot.domain.service;

import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.component.ToolComponent;
import me.golemcore.bot.domain.loop.AgentContextHolder;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.Attachment;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.ToolFailureKind;
import me.golemcore.bot.domain.model.ToolResult;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.plugin.context.PluginChannelCatalog;
import me.golemcore.bot.plugin.context.PluginPortResolver;
import me.golemcore.bot.plugin.context.PluginToolCatalog;
import me.golemcore.bot.port.inbound.ChannelPort;
import me.golemcore.bot.port.outbound.ConfirmationPort;
import org.springframework.stereotype.Component;

import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

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
    private final PluginPortResolver pluginPortResolver;
    private final BotProperties properties;
    private final PluginChannelCatalog pluginChannelCatalog;

    public ToolCallExecutionService(PluginToolCatalog pluginToolCatalog,
            ToolConfirmationPolicy confirmationPolicy,
            PluginPortResolver pluginPortResolver,
            BotProperties properties,
            PluginChannelCatalog pluginChannelCatalog) {
        this.toolRegistry = new ConcurrentHashMap<>();
        registerCatalogTools(pluginToolCatalog);
        this.confirmationPolicy = confirmationPolicy;
        this.pluginPortResolver = pluginPortResolver;
        this.properties = properties;
        this.pluginChannelCatalog = pluginChannelCatalog;
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
            } else if (!confirmationPolicy.isEnabled() && confirmationPolicy.isNotableAction(toolCall)) {
                notifyToolExecution(context, toolCall);
            }

            ToolResult result = executeToolCall(toolCall);
            context.addToolResult(toolCall.getId(), result);
            Attachment attachment = extractAttachment(context, result, toolCall.getName());

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

    private boolean requiresConfirmation(Message.ToolCall toolCall) {
        ConfirmationPort confirmationPort = pluginPortResolver.requireConfirmationPort();
        return confirmationPolicy.requiresConfirmation(toolCall) && confirmationPort.isAvailable();
    }

    private boolean requestConfirmation(AgentContext context, Message.ToolCall toolCall) {
        ConfirmationPort confirmationPort = pluginPortResolver.requireConfirmationPort();
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

    private ToolResult executeToolCall(Message.ToolCall toolCall) {
        String toolName = sanitizeToolName(toolCall.getName());
        ToolComponent tool = toolRegistry.get(toolName);

        if (tool == null) {
            String available = String.join(", ", toolRegistry.keySet());
            return ToolResult.failure(ToolFailureKind.POLICY_DENIED,
                    "Unknown tool: " + toolName + ". Available tools: " + available);
        }

        if (!tool.isEnabled()) {
            return ToolResult.failure(ToolFailureKind.POLICY_DENIED, "Tool is disabled: " + toolCall.getName());
        }

        try {
            CompletableFuture<ToolResult> future = tool.execute(toolCall.getArguments());
            return future.get(TOOL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.error("Tool execution failed: {}", toolCall.getName(), e);
            return ToolResult.failure(ToolFailureKind.EXECUTION_FAILED,
                    "Tool execution failed: " + safeCauseMessage(e));
        }
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

    private void notifyToolExecution(AgentContext context, Message.ToolCall toolCall) {
        String description = confirmationPolicy.describeAction(toolCall);
        String content = "Executing tool: " + toolCall.getName() + "\n" + description;

        try {
            String chatId = SessionIdentitySupport.resolveTransportChatId(context.getSession());
            ChannelPort channel = getChannelPort(context.getSession().getChannelType());
            if (channel != null) {
                channel.sendMessage(chatId, content);
            }
        } catch (Exception e) {
            log.warn("[Tools] Notification failed", e);
        }
    }

    private ChannelPort getChannelPort(String channelType) {
        if (channelType == null) {
            return null;
        }
        return pluginChannelCatalog.getChannel(channelType);
    }

    private static String resolveToolName(ToolComponent tool) {
        if (tool == null) {
            return null;
        }
        try {
            String toolName = tool.getToolName();
            if (toolName != null && !toolName.isBlank()) {
                return toolName;
            }
            if (tool.getDefinition() != null
                    && tool.getDefinition().getName() != null
                    && !tool.getDefinition().getName().isBlank()) {
                return tool.getDefinition().getName();
            }
            return null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private void registerCatalogTools(PluginToolCatalog pluginToolCatalog) {
        if (pluginToolCatalog == null) {
            return;
        }
        for (ToolComponent tool : pluginToolCatalog.getAllTools()) {
            String toolName = resolveToolName(tool);
            if (toolName != null) {
                toolRegistry.put(toolName, tool);
            }
        }
    }

    /**
     * Truncate tool result content that exceeds the configured max length.
     */
    public String truncateToolResult(String content, String toolName) {
        if (content == null) {
            return null;
        }
        int maxChars = properties.getAutoCompact().getMaxToolResultChars();
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
}
