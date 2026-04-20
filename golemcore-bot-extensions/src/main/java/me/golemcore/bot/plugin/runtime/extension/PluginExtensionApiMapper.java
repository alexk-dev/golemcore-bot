package me.golemcore.bot.plugin.runtime.extension;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Explicit host-to-plugin extension API mapping.
 */
@Component
public class PluginExtensionApiMapper {

    public me.golemcore.bot.domain.model.ToolDefinition toHostToolDefinition(
            me.golemcore.plugin.api.extension.model.ToolDefinition definition) {
        if (definition == null) {
            return null;
        }
        return me.golemcore.bot.domain.model.ToolDefinition.builder()
                .name(definition.getName())
                .description(definition.getDescription())
                .inputSchema(copyMap(definition.getInputSchema()))
                .build();
    }

    public me.golemcore.plugin.api.extension.model.ToolDefinition toPluginToolDefinition(
            me.golemcore.bot.domain.model.ToolDefinition definition) {
        if (definition == null) {
            return null;
        }
        return me.golemcore.plugin.api.extension.model.ToolDefinition.builder()
                .name(definition.getName())
                .description(definition.getDescription())
                .inputSchema(copyMap(definition.getInputSchema()))
                .build();
    }

    public me.golemcore.bot.domain.model.ToolResult toHostToolResult(
            me.golemcore.plugin.api.extension.model.ToolResult result) {
        if (result == null) {
            return null;
        }
        return me.golemcore.bot.domain.model.ToolResult.builder()
                .success(result.isSuccess())
                .output(result.getOutput())
                .data(copyObject(result.getData()))
                .error(result.getError())
                .failureKind(toHostToolFailureKind(result.getFailureKind()))
                .build();
    }

    public me.golemcore.plugin.api.extension.model.ToolResult toPluginToolResult(
            me.golemcore.bot.domain.model.ToolResult result) {
        if (result == null) {
            return null;
        }
        return me.golemcore.plugin.api.extension.model.ToolResult.builder()
                .success(result.isSuccess())
                .output(result.getOutput())
                .data(copyObject(result.getData()))
                .error(result.getError())
                .failureKind(toPluginToolFailureKind(result.getFailureKind()))
                .build();
    }

    public Map<String, Object> copyGenericMap(Map<String, Object> source) {
        return copyMap(source);
    }

    public Object copyGenericObject(Object source) {
        return copyObject(source);
    }

    public me.golemcore.bot.domain.model.Message toHostMessage(
            me.golemcore.plugin.api.extension.model.Message message) {
        if (message == null) {
            return null;
        }
        return me.golemcore.bot.domain.model.Message.builder()
                .id(message.getId())
                .role(message.getRole())
                .content(message.getContent())
                .channelType(message.getChannelType())
                .chatId(message.getChatId())
                .senderId(message.getSenderId())
                .toolCalls(toHostToolCalls(message.getToolCalls()))
                .toolCallId(message.getToolCallId())
                .toolName(message.getToolName())
                .metadata(copyMap(message.getMetadata()))
                .timestamp(message.getTimestamp())
                .voiceData(copyBytes(message.getVoiceData()))
                .voiceTranscription(message.getVoiceTranscription())
                .audioFormat(toHostAudioFormat(message.getAudioFormat()))
                .build();
    }

    public me.golemcore.plugin.api.extension.model.Message toPluginMessage(
            me.golemcore.bot.domain.model.Message message) {
        if (message == null) {
            return null;
        }
        return me.golemcore.plugin.api.extension.model.Message.builder()
                .id(message.getId())
                .role(message.getRole())
                .content(message.getContent())
                .channelType(message.getChannelType())
                .chatId(message.getChatId())
                .senderId(message.getSenderId())
                .toolCalls(toPluginToolCalls(message.getToolCalls()))
                .toolCallId(message.getToolCallId())
                .toolName(message.getToolName())
                .metadata(copyMap(message.getMetadata()))
                .timestamp(message.getTimestamp())
                .voiceData(copyBytes(message.getVoiceData()))
                .voiceTranscription(message.getVoiceTranscription())
                .audioFormat(toPluginAudioFormat(message.getAudioFormat()))
                .build();
    }

    public me.golemcore.bot.domain.model.AudioFormat toHostAudioFormat(
            me.golemcore.plugin.api.extension.model.AudioFormat format) {
        return format == null ? null : me.golemcore.bot.domain.model.AudioFormat.valueOf(format.name());
    }

    public me.golemcore.plugin.api.extension.model.AudioFormat toPluginAudioFormat(
            me.golemcore.bot.domain.model.AudioFormat format) {
        return format == null ? null : me.golemcore.plugin.api.extension.model.AudioFormat.valueOf(format.name());
    }

    public me.golemcore.bot.domain.model.AgentSession toHostAgentSession(
            me.golemcore.plugin.api.extension.model.AgentSession session) {
        if (session == null) {
            return null;
        }
        return me.golemcore.bot.domain.model.AgentSession.builder()
                .id(session.getId())
                .channelType(session.getChannelType())
                .chatId(session.getChatId())
                .messages(toHostMessages(session.getMessages()))
                .metadata(copyMap(session.getMetadata()))
                .state(toHostSessionState(session.getState()))
                .createdAt(session.getCreatedAt())
                .updatedAt(session.getUpdatedAt())
                .build();
    }

    public me.golemcore.plugin.api.extension.model.AgentSession toPluginAgentSession(
            me.golemcore.bot.domain.model.AgentSession session) {
        if (session == null) {
            return null;
        }
        return me.golemcore.plugin.api.extension.model.AgentSession.builder()
                .id(session.getId())
                .channelType(session.getChannelType())
                .chatId(session.getChatId())
                .messages(toPluginMessages(session.getMessages()))
                .metadata(copyMap(session.getMetadata()))
                .state(toPluginSessionState(session.getState()))
                .createdAt(session.getCreatedAt())
                .updatedAt(session.getUpdatedAt())
                .build();
    }

    public List<me.golemcore.plugin.api.extension.model.AgentSession> toPluginAgentSessions(
            List<me.golemcore.bot.domain.model.AgentSession> sessions) {
        if (sessions == null || sessions.isEmpty()) {
            return List.of();
        }
        return sessions.stream()
                .map(this::toPluginAgentSession)
                .toList();
    }

    public List<me.golemcore.plugin.api.extension.model.Message> toPluginMessages(
            List<me.golemcore.bot.domain.model.Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        return messages.stream()
                .map(this::toPluginMessage)
                .toList();
    }

    public me.golemcore.bot.port.outbound.VoicePort.TranscriptionResult toHostTranscriptionResult(
            me.golemcore.plugin.api.extension.port.outbound.VoicePort.TranscriptionResult result) {
        if (result == null) {
            return null;
        }
        return new me.golemcore.bot.port.outbound.VoicePort.TranscriptionResult(
                result.text(),
                result.language(),
                result.confidence(),
                result.duration(),
                toHostWordTimestamps(result.words()));
    }

    public me.golemcore.plugin.api.extension.port.outbound.VoicePort.TranscriptionResult toPluginTranscriptionResult(
            me.golemcore.bot.port.outbound.VoicePort.TranscriptionResult result) {
        if (result == null) {
            return null;
        }
        return new me.golemcore.plugin.api.extension.port.outbound.VoicePort.TranscriptionResult(
                result.text(),
                result.language(),
                result.confidence(),
                result.duration(),
                toPluginWordTimestamps(result.words()));
    }

    public me.golemcore.bot.port.outbound.VoicePort.VoiceConfig toHostVoiceConfig(
            me.golemcore.plugin.api.extension.port.outbound.VoicePort.VoiceConfig config) {
        if (config == null) {
            return null;
        }
        return new me.golemcore.bot.port.outbound.VoicePort.VoiceConfig(
                config.voiceId(),
                config.modelId(),
                config.speed(),
                toHostAudioFormat(config.outputFormat()));
    }

    public me.golemcore.plugin.api.extension.port.outbound.VoicePort.VoiceConfig toPluginVoiceConfig(
            me.golemcore.bot.port.outbound.VoicePort.VoiceConfig config) {
        if (config == null) {
            return null;
        }
        return new me.golemcore.plugin.api.extension.port.outbound.VoicePort.VoiceConfig(
                config.voiceId(),
                config.modelId(),
                config.speed(),
                toPluginAudioFormat(config.outputFormat()));
    }

    public me.golemcore.plugin.api.extension.port.inbound.CommandPort.CommandResult toPluginCommandResult(
            me.golemcore.bot.port.inbound.CommandPort.CommandResult result) {
        if (result == null) {
            return null;
        }
        return new me.golemcore.plugin.api.extension.port.inbound.CommandPort.CommandResult(
                result.success(),
                result.output(),
                copyObject(result.data()));
    }

    public me.golemcore.plugin.api.extension.model.ProgressUpdate toPluginProgressUpdate(
            me.golemcore.bot.domain.model.ProgressUpdate update) {
        if (update == null) {
            return null;
        }
        return new me.golemcore.plugin.api.extension.model.ProgressUpdate(
                me.golemcore.plugin.api.extension.model.ProgressUpdateType.valueOf(update.type().name()),
                update.text(),
                copyMap(update.metadata()));
    }

    public List<me.golemcore.plugin.api.extension.port.inbound.CommandPort.CommandDefinition> toPluginCommandDefinitions(
            List<me.golemcore.bot.port.inbound.CommandPort.CommandDefinition> definitions) {
        if (definitions == null || definitions.isEmpty()) {
            return List.of();
        }
        return definitions.stream()
                .map(definition -> new me.golemcore.plugin.api.extension.port.inbound.CommandPort.CommandDefinition(
                        definition.name(),
                        definition.description(),
                        definition.usage()))
                .toList();
    }

    public me.golemcore.plugin.api.extension.model.PlanReadyEvent toPluginPlanReadyEvent(
            me.golemcore.bot.domain.model.PlanReadyEvent event) {
        return event == null ? null
                : new me.golemcore.plugin.api.extension.model.PlanReadyEvent(event.planId(), event.chatId());
    }

    public me.golemcore.plugin.api.extension.model.PlanExecutionCompletedEvent toPluginPlanExecutionCompletedEvent(
            me.golemcore.bot.domain.model.PlanExecutionCompletedEvent event) {
        return event == null
                ? null
                : new me.golemcore.plugin.api.extension.model.PlanExecutionCompletedEvent(
                        event.planId(),
                        event.chatId(),
                        event.summary());
    }

    public me.golemcore.plugin.api.extension.model.TelegramRestartEvent toPluginTelegramRestartEvent(
            me.golemcore.bot.domain.model.TelegramRestartEvent event) {
        return event == null ? null : new me.golemcore.plugin.api.extension.model.TelegramRestartEvent();
    }

    private List<me.golemcore.bot.domain.model.Message> toHostMessages(
            List<me.golemcore.plugin.api.extension.model.Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        return messages.stream()
                .map(this::toHostMessage)
                .toList();
    }

    private List<me.golemcore.bot.domain.model.Message.ToolCall> toHostToolCalls(
            List<me.golemcore.plugin.api.extension.model.Message.ToolCall> toolCalls) {
        if (toolCalls == null) {
            return List.of();
        }
        List<me.golemcore.bot.domain.model.Message.ToolCall> copied = new ArrayList<>(toolCalls.size());
        for (me.golemcore.plugin.api.extension.model.Message.ToolCall toolCall : toolCalls) {
            if (toolCall == null) {
                copied.add(null);
                continue;
            }
            copied.add(me.golemcore.bot.domain.model.Message.ToolCall.builder()
                    .id(toolCall.getId())
                    .name(toolCall.getName())
                    .arguments(copyMap(toolCall.getArguments()))
                    .build());
        }
        return copied;
    }

    private List<me.golemcore.plugin.api.extension.model.Message.ToolCall> toPluginToolCalls(
            List<me.golemcore.bot.domain.model.Message.ToolCall> toolCalls) {
        if (toolCalls == null) {
            return List.of();
        }
        List<me.golemcore.plugin.api.extension.model.Message.ToolCall> copied = new ArrayList<>(toolCalls.size());
        for (me.golemcore.bot.domain.model.Message.ToolCall toolCall : toolCalls) {
            if (toolCall == null) {
                copied.add(null);
                continue;
            }
            copied.add(me.golemcore.plugin.api.extension.model.Message.ToolCall.builder()
                    .id(toolCall.getId())
                    .name(toolCall.getName())
                    .arguments(copyMap(toolCall.getArguments()))
                    .build());
        }
        return copied;
    }

    private me.golemcore.bot.domain.model.AgentSession.SessionState toHostSessionState(
            me.golemcore.plugin.api.extension.model.AgentSession.SessionState state) {
        return state == null ? null : me.golemcore.bot.domain.model.AgentSession.SessionState.valueOf(state.name());
    }

    private me.golemcore.plugin.api.extension.model.AgentSession.SessionState toPluginSessionState(
            me.golemcore.bot.domain.model.AgentSession.SessionState state) {
        return state == null ? null
                : me.golemcore.plugin.api.extension.model.AgentSession.SessionState.valueOf(state.name());
    }

    private List<me.golemcore.bot.port.outbound.VoicePort.WordTimestamp> toHostWordTimestamps(
            List<me.golemcore.plugin.api.extension.port.outbound.VoicePort.WordTimestamp> words) {
        if (words == null || words.isEmpty()) {
            return List.of();
        }
        return words.stream()
                .map(word -> new me.golemcore.bot.port.outbound.VoicePort.WordTimestamp(
                        word.word(),
                        word.start(),
                        word.end()))
                .toList();
    }

    private List<me.golemcore.plugin.api.extension.port.outbound.VoicePort.WordTimestamp> toPluginWordTimestamps(
            List<me.golemcore.bot.port.outbound.VoicePort.WordTimestamp> words) {
        if (words == null || words.isEmpty()) {
            return List.of();
        }
        return words.stream()
                .map(word -> new me.golemcore.plugin.api.extension.port.outbound.VoicePort.WordTimestamp(
                        word.word(),
                        word.start(),
                        word.end()))
                .toList();
    }

    private me.golemcore.bot.domain.model.ToolFailureKind toHostToolFailureKind(
            me.golemcore.plugin.api.extension.model.ToolFailureKind kind) {
        return kind == null ? null : me.golemcore.bot.domain.model.ToolFailureKind.valueOf(kind.name());
    }

    private me.golemcore.plugin.api.extension.model.ToolFailureKind toPluginToolFailureKind(
            me.golemcore.bot.domain.model.ToolFailureKind kind) {
        return kind == null ? null : me.golemcore.plugin.api.extension.model.ToolFailureKind.valueOf(kind.name());
    }

    private me.golemcore.bot.domain.model.Attachment toHostAttachment(
            me.golemcore.plugin.api.extension.model.Attachment attachment) {
        if (attachment == null) {
            return null;
        }
        return me.golemcore.bot.domain.model.Attachment.builder()
                .type(attachment.getType() == null
                        ? null
                        : me.golemcore.bot.domain.model.Attachment.Type.valueOf(attachment.getType().name()))
                .data(copyBytes(attachment.getData()))
                .filename(attachment.getFilename())
                .mimeType(attachment.getMimeType())
                .caption(attachment.getCaption())
                .build();
    }

    private me.golemcore.plugin.api.extension.model.Attachment toPluginAttachment(
            me.golemcore.bot.domain.model.Attachment attachment) {
        if (attachment == null) {
            return null;
        }
        return me.golemcore.plugin.api.extension.model.Attachment.builder()
                .type(attachment.getType() == null
                        ? null
                        : me.golemcore.plugin.api.extension.model.Attachment.Type.valueOf(attachment.getType().name()))
                .data(copyBytes(attachment.getData()))
                .filename(attachment.getFilename())
                .mimeType(attachment.getMimeType())
                .caption(attachment.getCaption())
                .build();
    }

    private Map<String, Object> copyMap(Map<String, Object> source) {
        if (source == null) {
            return Map.of();
        }
        Map<String, Object> copied = new LinkedHashMap<>();
        source.forEach((key, value) -> copied.put(key, copyObject(value)));
        return copied;
    }

    private static byte[] copyBytes(byte[] source) {
        return source == null ? null : source.clone();
    }

    private Object copyObject(Object source) {
        if (source == null) {
            return null;
        }
        if (source instanceof me.golemcore.plugin.api.extension.model.Attachment attachment) {
            return toHostAttachment(attachment);
        }
        if (source instanceof me.golemcore.bot.domain.model.Attachment attachment) {
            return toPluginAttachment(attachment);
        }
        if (source instanceof Map<?, ?> map) {
            Map<Object, Object> copied = new LinkedHashMap<>();
            map.forEach((key, value) -> copied.put(copyObject(key), copyObject(value)));
            return copied;
        }
        if (source instanceof List<?> list) {
            List<Object> copied = new ArrayList<>(list.size());
            list.forEach(item -> copied.add(copyObject(item)));
            return copied;
        }
        if (source instanceof byte[] bytes) {
            return copyBytes(bytes);
        }
        return source;
    }
}
