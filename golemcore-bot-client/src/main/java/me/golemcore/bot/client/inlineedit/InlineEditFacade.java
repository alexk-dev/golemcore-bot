package me.golemcore.bot.client.inlineedit;

import me.golemcore.bot.client.dto.InlineEditRequest;
import me.golemcore.bot.client.dto.InlineEditResponse;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.LlmRequest;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.port.outbound.LlmPort;
import me.golemcore.bot.port.outbound.WorkspaceEditorPort;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class InlineEditFacade {

    private static final int MAX_TOKENS = 1200;
    private static final int TIMEOUT_SECONDS = 30;

    private final WorkspaceEditorPort workspaceEditorPort;
    private final LlmPort llmPort;
    private final InlineEditPromptFactory promptFactory;

    public InlineEditFacade(
            WorkspaceEditorPort workspaceEditorPort,
            LlmPort llmPort,
            InlineEditPromptFactory promptFactory) {
        this.workspaceEditorPort = workspaceEditorPort;
        this.llmPort = llmPort;
        this.promptFactory = promptFactory;
    }

    public InlineEditResponse createInlineEdit(InlineEditRequest request, String clientInstanceId) {
        if (request == null) {
            throw new IllegalArgumentException("Request is required");
        }

        String path = request.getPath();
        String content = request.getContent();
        Integer selectionFrom = request.getSelectionFrom();
        Integer selectionTo = request.getSelectionTo();
        String selectedText = request.getSelectedText();
        String instruction = request.getInstruction();

        validateRequest(path, content, selectionFrom, selectionTo, selectedText, instruction);
        workspaceEditorPort.validateEditablePath(path);

        String prompt = promptFactory.buildPrompt(selectedText, instruction);
        Message message = Message.builder()
                .role("user")
                .content(prompt)
                .metadata(buildMetadata(path, selectionFrom, selectionTo, selectedText, instruction, clientInstanceId))
                .build();
        LlmRequest llmRequest = LlmRequest.builder()
                .model(resolveModel())
                .messages(List.of(message))
                .temperature(0.0)
                .maxTokens(MAX_TOKENS)
                .build();

        try {
            LlmResponse response = llmPort.chat(llmRequest).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            String replacement = sanitizeReplacement(response != null ? response.getContent() : null);
            if (replacement.isBlank()) {
                throw new IllegalStateException("Inline edit returned empty content");
            }
            return InlineEditResponse.builder()
                    .path(path)
                    .replacement(replacement)
                    .build();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Inline edit failed: interrupted", exception);
        } catch (ExecutionException | TimeoutException exception) {
            String messageText = exception.getCause() != null && exception.getCause().getMessage() != null
                    ? exception.getCause().getMessage()
                    : exception.getMessage();
            throw new IllegalStateException("Inline edit failed: " + messageText, exception);
        }
    }

    private void validateRequest(
            String path,
            String content,
            Integer selectionFrom,
            Integer selectionTo,
            String selectedText,
            String instruction) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("Path is required");
        }
        if (content == null) {
            throw new IllegalArgumentException("Content is required");
        }
        if (instruction == null || instruction.trim().isBlank()) {
            throw new IllegalArgumentException("Instruction is required");
        }
        if (selectionFrom == null || selectionTo == null || selectionFrom < 0 || selectionTo < 0
                || selectionTo <= selectionFrom) {
            throw new IllegalArgumentException("Selection range is invalid");
        }
        if (selectionTo > content.length()) {
            throw new IllegalArgumentException("Selection range exceeds file content");
        }
        String actualSelectedText = content.substring(selectionFrom, selectionTo);
        if (!actualSelectedText.equals(selectedText)) {
            throw new IllegalArgumentException("Selected text does not match file content");
        }
    }

    private Map<String, Object> buildMetadata(
            String path,
            Integer selectionFrom,
            Integer selectionTo,
            String selectedText,
            String instruction,
            String clientInstanceId) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(ContextAttributes.WEB_INLINE_EDIT_PATH, path);
        metadata.put(ContextAttributes.WEB_SELECTION_FROM, selectionFrom);
        metadata.put(ContextAttributes.WEB_SELECTION_TO, selectionTo);
        metadata.put(ContextAttributes.WEB_SELECTION_TEXT, selectedText);
        metadata.put(ContextAttributes.WEB_INLINE_EDIT_INSTRUCTION, instruction);
        if (clientInstanceId != null && !clientInstanceId.isBlank()) {
            metadata.put(ContextAttributes.WEB_CLIENT_INSTANCE_ID, clientInstanceId);
        }
        return metadata;
    }

    private String resolveModel() {
        String currentModel = llmPort.getCurrentModel();
        return currentModel != null && !currentModel.isBlank() ? currentModel : null;
    }

    private String sanitizeReplacement(String content) {
        if (content == null) {
            return "";
        }
        String trimmed = content.trim();
        if (trimmed.startsWith("```") && trimmed.endsWith("```")) {
            String[] parts = trimmed.split("\n", -1);
            if (parts.length >= 2) {
                StringBuilder builder = new StringBuilder();
                for (int index = 1; index < parts.length - 1; index += 1) {
                    if (builder.length() > 0) {
                        builder.append('\n');
                    }
                    builder.append(parts[index]);
                }
                return builder.toString().strip();
            }
        }
        return trimmed;
    }
}
