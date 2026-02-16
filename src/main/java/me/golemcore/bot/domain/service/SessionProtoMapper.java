package me.golemcore.bot.domain.service;

import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.AudioFormat;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.proto.session.v1.AgentSessionRecord;
import me.golemcore.bot.proto.session.v1.JsonArray;
import me.golemcore.bot.proto.session.v1.JsonObject;
import me.golemcore.bot.proto.session.v1.JsonValue;
import me.golemcore.bot.proto.session.v1.MessageRecord;
import me.golemcore.bot.proto.session.v1.NullValue;
import me.golemcore.bot.proto.session.v1.SessionState;
import me.golemcore.bot.proto.session.v1.ToolCallRecord;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts {@link AgentSession} objects to/from protobuf records.
 */
public class SessionProtoMapper {

    public AgentSessionRecord toProto(AgentSession session) {
        AgentSessionRecord.Builder builder = AgentSessionRecord.newBuilder();
        putIfNotBlank(session.getId(), builder::setId);
        putIfNotBlank(session.getChannelType(), builder::setChannelType);
        putIfNotBlank(session.getChatId(), builder::setChatId);
        if (session.getMessages() != null) {
            for (Message message : session.getMessages()) {
                builder.addMessages(toProtoMessage(message));
            }
        }
        if (session.getMetadata() != null) {
            for (Map.Entry<String, Object> entry : session.getMetadata().entrySet()) {
                if (entry.getKey() != null) {
                    builder.putMetadata(entry.getKey(), toJsonValue(entry.getValue()));
                }
            }
        }
        if (session.getState() != null) {
            builder.setState(toProtoSessionState(session.getState()));
        }
        if (session.getCreatedAt() != null) {
            builder.setCreatedAt(toTimestamp(session.getCreatedAt()));
        }
        if (session.getUpdatedAt() != null) {
            builder.setUpdatedAt(toTimestamp(session.getUpdatedAt()));
        }
        return builder.build();
    }

    public AgentSession fromProto(AgentSessionRecord record) {
        AgentSession.AgentSessionBuilder builder = AgentSession.builder()
                .id(blankToNull(record.getId()))
                .channelType(blankToNull(record.getChannelType()))
                .chatId(blankToNull(record.getChatId()))
                .messages(fromProtoMessages(record.getMessagesList()))
                .metadata(fromProtoMap(record.getMetadataMap()))
                .state(fromProtoSessionState(record.getState()));

        if (record.hasCreatedAt()) {
            builder.createdAt(fromTimestamp(record.getCreatedAt()));
        }
        if (record.hasUpdatedAt()) {
            builder.updatedAt(fromTimestamp(record.getUpdatedAt()));
        }
        return builder.build();
    }

    private List<Message> fromProtoMessages(List<MessageRecord> records) {
        List<Message> messages = new ArrayList<>();
        for (MessageRecord record : records) {
            messages.add(fromProtoMessage(record));
        }
        return messages;
    }

    private MessageRecord toProtoMessage(Message message) {
        MessageRecord.Builder builder = MessageRecord.newBuilder();
        putIfNotBlank(message.getId(), builder::setId);
        putIfNotBlank(message.getRole(), builder::setRole);
        putIfNotBlank(message.getContent(), builder::setContent);
        putIfNotBlank(message.getChannelType(), builder::setChannelType);
        putIfNotBlank(message.getChatId(), builder::setChatId);
        putIfNotBlank(message.getSenderId(), builder::setSenderId);
        putIfNotBlank(message.getToolCallId(), builder::setToolCallId);
        putIfNotBlank(message.getToolName(), builder::setToolName);
        putIfNotBlank(message.getVoiceTranscription(), builder::setVoiceTranscription);

        if (message.getTimestamp() != null) {
            builder.setTimestamp(toTimestamp(message.getTimestamp()));
        }
        if (message.getVoiceData() != null && message.getVoiceData().length > 0) {
            builder.setVoiceData(ByteString.copyFrom(message.getVoiceData()));
        }
        if (message.getAudioFormat() != null) {
            builder.setAudioFormat(toProtoAudioFormat(message.getAudioFormat()));
        }
        if (message.getToolCalls() != null) {
            for (Message.ToolCall toolCall : message.getToolCalls()) {
                builder.addToolCalls(toProtoToolCall(toolCall));
            }
        }
        if (message.getMetadata() != null) {
            for (Map.Entry<String, Object> entry : message.getMetadata().entrySet()) {
                if (entry.getKey() != null) {
                    builder.putMetadata(entry.getKey(), toJsonValue(entry.getValue()));
                }
            }
        }
        return builder.build();
    }

    private Message fromProtoMessage(MessageRecord record) {
        Message.MessageBuilder builder = Message.builder()
                .id(blankToNull(record.getId()))
                .role(blankToNull(record.getRole()))
                .content(blankToNull(record.getContent()))
                .channelType(blankToNull(record.getChannelType()))
                .chatId(blankToNull(record.getChatId()))
                .senderId(blankToNull(record.getSenderId()))
                .toolCallId(blankToNull(record.getToolCallId()))
                .toolName(blankToNull(record.getToolName()))
                .voiceTranscription(blankToNull(record.getVoiceTranscription()))
                .toolCalls(fromProtoToolCalls(record.getToolCallsList()))
                .metadata(fromProtoMap(record.getMetadataMap()));

        if (record.hasTimestamp()) {
            builder.timestamp(fromTimestamp(record.getTimestamp()));
        }
        if (!record.getVoiceData().isEmpty()) {
            builder.voiceData(record.getVoiceData().toByteArray());
        }
        if (record.getAudioFormat() != me.golemcore.bot.proto.session.v1.AudioFormat.AUDIO_FORMAT_UNSPECIFIED) {
            builder.audioFormat(fromProtoAudioFormat(record.getAudioFormat()));
        }
        return builder.build();
    }

    private ToolCallRecord toProtoToolCall(Message.ToolCall toolCall) {
        ToolCallRecord.Builder builder = ToolCallRecord.newBuilder();
        putIfNotBlank(toolCall.getId(), builder::setId);
        putIfNotBlank(toolCall.getName(), builder::setName);
        if (toolCall.getArguments() != null) {
            for (Map.Entry<String, Object> entry : toolCall.getArguments().entrySet()) {
                if (entry.getKey() != null) {
                    builder.putArguments(entry.getKey(), toJsonValue(entry.getValue()));
                }
            }
        }
        return builder.build();
    }

    private List<Message.ToolCall> fromProtoToolCalls(List<ToolCallRecord> records) {
        List<Message.ToolCall> toolCalls = new ArrayList<>();
        for (ToolCallRecord record : records) {
            toolCalls.add(Message.ToolCall.builder()
                    .id(blankToNull(record.getId()))
                    .name(blankToNull(record.getName()))
                    .arguments(fromProtoMap(record.getArgumentsMap()))
                    .build());
        }
        return toolCalls;
    }

    private JsonValue toJsonValue(Object value) {
        JsonValue.Builder builder = JsonValue.newBuilder();
        if (value == null) {
            return builder.setNullValue(NullValue.newBuilder().build()).build();
        }
        if (value instanceof Boolean) {
            return builder.setBoolValue((Boolean) value).build();
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            return builder.setIntValue(((Number) value).longValue()).build();
        }
        if (value instanceof BigInteger) {
            BigInteger bigInteger = (BigInteger) value;
            if (bigInteger.bitLength() <= 63) {
                return builder.setIntValue(bigInteger.longValue()).build();
            }
            return builder.setStringValue(bigInteger.toString()).build();
        }
        if (value instanceof Float || value instanceof Double || value instanceof BigDecimal) {
            return builder.setDoubleValue(((Number) value).doubleValue()).build();
        }
        if (value instanceof Number) {
            return builder.setDoubleValue(((Number) value).doubleValue()).build();
        }
        if (value instanceof String) {
            return builder.setStringValue((String) value).build();
        }
        if (value instanceof Map<?, ?>) {
            JsonObject.Builder objectBuilder = JsonObject.newBuilder();
            Map<?, ?> map = (Map<?, ?>) value;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() instanceof String) {
                    objectBuilder.putFields((String) entry.getKey(), toJsonValue(entry.getValue()));
                }
            }
            return builder.setObjectValue(objectBuilder.build()).build();
        }
        if (value instanceof List<?>) {
            JsonArray.Builder arrayBuilder = JsonArray.newBuilder();
            List<?> list = (List<?>) value;
            for (Object item : list) {
                arrayBuilder.addItems(toJsonValue(item));
            }
            return builder.setArrayValue(arrayBuilder.build()).build();
        }
        return builder.setStringValue(String.valueOf(value)).build();
    }

    private Map<String, Object> fromProtoMap(Map<String, JsonValue> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, JsonValue> entry : source.entrySet()) {
            result.put(entry.getKey(), fromJsonValue(entry.getValue()));
        }
        return result;
    }

    private Object fromJsonValue(JsonValue value) {
        return switch (value.getKindCase()) {
        case BOOL_VALUE -> value.getBoolValue();
        case INT_VALUE -> value.getIntValue();
        case DOUBLE_VALUE -> value.getDoubleValue();
        case STRING_VALUE -> value.getStringValue();
        case OBJECT_VALUE -> fromProtoMap(value.getObjectValue().getFieldsMap());
        case ARRAY_VALUE -> fromProtoArray(value.getArrayValue());
        case NULL_VALUE, KIND_NOT_SET -> null;
        };
    }

    private List<Object> fromProtoArray(JsonArray array) {
        List<Object> values = new ArrayList<>();
        for (JsonValue item : array.getItemsList()) {
            values.add(fromJsonValue(item));
        }
        return values;
    }

    private SessionState toProtoSessionState(AgentSession.SessionState state) {
        return switch (state) {
        case ACTIVE -> SessionState.SESSION_STATE_ACTIVE;
        case PAUSED -> SessionState.SESSION_STATE_PAUSED;
        case TERMINATED -> SessionState.SESSION_STATE_TERMINATED;
        };
    }

    private AgentSession.SessionState fromProtoSessionState(SessionState state) {
        return switch (state) {
        case SESSION_STATE_PAUSED -> AgentSession.SessionState.PAUSED;
        case SESSION_STATE_TERMINATED -> AgentSession.SessionState.TERMINATED;
        case SESSION_STATE_UNSPECIFIED, SESSION_STATE_ACTIVE, UNRECOGNIZED -> AgentSession.SessionState.ACTIVE;
        };
    }

    private me.golemcore.bot.proto.session.v1.AudioFormat toProtoAudioFormat(AudioFormat format) {
        return switch (format) {
        case OGG_OPUS -> me.golemcore.bot.proto.session.v1.AudioFormat.AUDIO_FORMAT_OGG_OPUS;
        case MP3 -> me.golemcore.bot.proto.session.v1.AudioFormat.AUDIO_FORMAT_MP3;
        case WAV -> me.golemcore.bot.proto.session.v1.AudioFormat.AUDIO_FORMAT_WAV;
        case PCM_16K -> me.golemcore.bot.proto.session.v1.AudioFormat.AUDIO_FORMAT_PCM_16K;
        case PCM_44K -> me.golemcore.bot.proto.session.v1.AudioFormat.AUDIO_FORMAT_PCM_44K;
        };
    }

    private AudioFormat fromProtoAudioFormat(me.golemcore.bot.proto.session.v1.AudioFormat format) {
        return switch (format) {
        case AUDIO_FORMAT_OGG_OPUS -> AudioFormat.OGG_OPUS;
        case AUDIO_FORMAT_MP3 -> AudioFormat.MP3;
        case AUDIO_FORMAT_WAV -> AudioFormat.WAV;
        case AUDIO_FORMAT_PCM_16K -> AudioFormat.PCM_16K;
        case AUDIO_FORMAT_PCM_44K -> AudioFormat.PCM_44K;
        case AUDIO_FORMAT_UNSPECIFIED, UNRECOGNIZED -> null;
        };
    }

    private Timestamp toTimestamp(Instant instant) {
        return Timestamp.newBuilder()
                .setSeconds(instant.getEpochSecond())
                .setNanos(instant.getNano())
                .build();
    }

    private Instant fromTimestamp(Timestamp timestamp) {
        return Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos());
    }

    private void putIfNotBlank(String value, java.util.function.Consumer<String> setter) {
        if (value != null && !value.isBlank()) {
            setter.accept(value);
        }
    }

    private String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value;
    }
}
