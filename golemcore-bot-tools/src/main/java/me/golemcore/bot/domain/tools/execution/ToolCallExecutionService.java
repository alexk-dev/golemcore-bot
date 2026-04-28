package me.golemcore.bot.domain.tools.execution;

import lombok.extern.slf4j.Slf4j;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import me.golemcore.bot.domain.component.ToolComponent;
import me.golemcore.bot.domain.loop.AgentContextHolder;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.Attachment;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.ToolFailureKind;
import me.golemcore.bot.domain.model.ToolResult;
import me.golemcore.bot.domain.tools.artifacts.ToolArtifactPersister;
import me.golemcore.bot.domain.tools.registry.ToolRegistryService;
import me.golemcore.bot.port.outbound.ConfirmationPort;
import org.springframework.stereotype.Component;

/**
 * Pure tool-call execution service: executes tools + confirmation gating + truncation + attachment extraction.
 * <p>
 * Does NOT mutate conversation history and does NOT set any loop-control flags.
 */
@Component
@Slf4j
public class ToolCallExecutionService {

    private static final long TOOL_TIMEOUT_SECONDS = 30;

    private final ToolRegistryService toolRegistryService;
    private final ToolConfirmationPolicy confirmationPolicy;
    private final ConfirmationPort confirmationPort;
    private final ToolAttachmentExtractor attachmentExtractor;
    private final ToolArtifactPersister artifactPersister;
    private final ToolResultPostProcessor resultPostProcessor;

    public ToolCallExecutionService(ToolRegistryService toolRegistryService, ToolConfirmationPolicy confirmationPolicy,
            ConfirmationPort confirmationPort, ToolAttachmentExtractor attachmentExtractor,
            ToolArtifactPersister artifactPersister, ToolResultPostProcessor resultPostProcessor) {
        this.toolRegistryService = Objects.requireNonNull(toolRegistryService, "toolRegistryService must not be null");
        this.confirmationPolicy = Objects.requireNonNull(confirmationPolicy, "confirmationPolicy must not be null");
        this.confirmationPort = Objects.requireNonNull(confirmationPort, "confirmationPort must not be null");
        this.attachmentExtractor = Objects.requireNonNull(attachmentExtractor, "attachmentExtractor must not be null");
        this.artifactPersister = Objects.requireNonNull(artifactPersister, "artifactPersister must not be null");
        this.resultPostProcessor = Objects.requireNonNull(resultPostProcessor, "resultPostProcessor must not be null");
    }

    public ToolCallExecutionResult execute(AgentContext context, Message.ToolCall toolCall) {
        return execute(ToolExecutionContext.from(context), toolCall);
    }

    public ToolCallExecutionResult execute(ToolExecutionContext executionContext, Message.ToolCall toolCall) {
        ToolExecutionContext safeExecutionContext = Objects.requireNonNull(executionContext,
                "executionContext must not be null");
        AgentContext context = safeExecutionContext.agentContext();
        AgentContextHolder.set(context);
        try {
            if (requiresConfirmation(toolCall)) {
                boolean approved = requestConfirmation(safeExecutionContext, toolCall);
                if (!approved) {
                    ToolResult denied = ToolResult.failure(ToolFailureKind.CONFIRMATION_DENIED, "Cancelled by user");
                    String content = resultPostProcessor.truncateToolResult("Error: Cancelled by user",
                            toolCall.getName());
                    context.addToolResult(toolCall.getId(), denied);
                    return new ToolCallExecutionResult(toolCall.getId(), toolCall.getName(), denied, content, null);
                }
            }

            ToolResult rawResult = executeToolCall(context, toolCall);
            Attachment attachment = attachmentExtractor.extract(rawResult, toolCall.getName());
            ToolResult result = artifactPersister.enrich(safeExecutionContext, rawResult, toolCall.getName(),
                    attachment);
            context.addToolResult(toolCall.getId(), result);

            String content = resultPostProcessor.buildToolMessageContent(result);
            content = resultPostProcessor.truncateToolResult(content, toolCall.getName());
            return new ToolCallExecutionResult(toolCall.getId(), toolCall.getName(), result, content, attachment);
        } finally {
            AgentContextHolder.clear();
        }
    }

    private boolean requiresConfirmation(Message.ToolCall toolCall) {
        return confirmationPolicy.requiresConfirmation(toolCall) && confirmationPort.isAvailable();
    }

    private boolean requestConfirmation(ToolExecutionContext executionContext, Message.ToolCall toolCall) {
        String chatId = executionContext.transportChatId();
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
            return ToolResult.failure(ToolFailureKind.EXECUTION_FAILED, "Tool execution interrupted");
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
        return toolRegistryService.getTool(toolName);
    }

    @SuppressWarnings("unchecked")
    private List<String> resolveAvailableToolNames(AgentContext context) {
        Set<String> names = new TreeSet<>(toolRegistryService.toolNames());
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
     * Strip special tokens and garbage from tool names. Some models (e.g. gpt-oss) leak special tokens like
     * {@code <|channel|>} into tool call names.
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

}
