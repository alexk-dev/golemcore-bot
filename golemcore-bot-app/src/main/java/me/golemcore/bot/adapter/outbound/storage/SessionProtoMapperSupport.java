package me.golemcore.bot.adapter.outbound.storage;

import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.AudioFormat;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.trace.TraceEventRecord;
import me.golemcore.bot.domain.model.trace.TraceRecord;
import me.golemcore.bot.domain.model.trace.TraceSnapshot;
import me.golemcore.bot.domain.model.trace.TraceSpanKind;
import me.golemcore.bot.domain.model.trace.TraceSpanRecord;
import me.golemcore.bot.domain.model.trace.TraceStatusCode;
import me.golemcore.bot.domain.model.trace.TraceStorageStats;
import me.golemcore.bot.proto.session.v1.AgentSessionRecord;
import me.golemcore.bot.proto.session.v1.JsonArray;
import me.golemcore.bot.proto.session.v1.JsonObject;
import me.golemcore.bot.proto.session.v1.JsonValue;
import me.golemcore.bot.proto.session.v1.MessageRecord;
import me.golemcore.bot.proto.session.v1.NullValue;
import me.golemcore.bot.proto.session.v1.SessionState;
import me.golemcore.bot.proto.session.v1.ToolCallRecord;

/**
 * Converts {@link AgentSession} objects to/from protobuf records.
 */
final class SessionProtoMapperSupport {

    private static final int LONG_BIT_LENGTH = 63;

    AgentSessionRecord toProto(AgentSession session) {
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
        if (session.getTraces() != null) {
            for (TraceRecord traceRecord : session.getTraces()) {
                if (traceRecord != null) {
                    builder.addTraces(toProtoTrace(traceRecord));
                }
            }
        }
        if (session.getTraceStorageStats() != null) {
            builder.setTraceStats(toProtoTraceStorageStats(session.getTraceStorageStats()));
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

    AgentSession fromProto(AgentSessionRecord record) {
        AgentSession.AgentSessionBuilder builder = AgentSession.builder()
                .id(blankToNull(record.getId()))
                .channelType(blankToNull(record.getChannelType()))
                .chatId(blankToNull(record.getChatId()))
                .messages(fromProtoMessages(record.getMessagesList()))
                .metadata(fromProtoMap(record.getMetadataMap()))
                .traces(fromProtoTraces(record.getTracesList()))
                .traceStorageStats(record.hasTraceStats()
                        ? fromProtoTraceStorageStats(record.getTraceStats())
                        : new TraceStorageStats())
                .state(fromProtoSessionState(record.getState()));

        if (record.hasCreatedAt()) {
            builder.createdAt(fromTimestamp(record.getCreatedAt()));
        }
        if (record.hasUpdatedAt()) {
            builder.updatedAt(fromTimestamp(record.getUpdatedAt()));
        }
        return builder.build();
    }

    private me.golemcore.bot.proto.session.v1.TraceStorageStats toProtoTraceStorageStats(TraceStorageStats stats) {
        return me.golemcore.bot.proto.session.v1.TraceStorageStats.newBuilder()
                .setCompressedSnapshotBytes(safeLong(stats.getCompressedSnapshotBytes()))
                .setUncompressedSnapshotBytes(safeLong(stats.getUncompressedSnapshotBytes()))
                .setEvictedSnapshots(safeInt(stats.getEvictedSnapshots()))
                .setEvictedTraces(safeInt(stats.getEvictedTraces()))
                .setTruncatedTraces(safeInt(stats.getTruncatedTraces()))
                .build();
    }

    private TraceStorageStats fromProtoTraceStorageStats(me.golemcore.bot.proto.session.v1.TraceStorageStats stats) {
        return TraceStorageStats.builder()
                .compressedSnapshotBytes(stats.getCompressedSnapshotBytes())
                .uncompressedSnapshotBytes(stats.getUncompressedSnapshotBytes())
                .evictedSnapshots((int) stats.getEvictedSnapshots())
                .evictedTraces((int) stats.getEvictedTraces())
                .truncatedTraces((int) stats.getTruncatedTraces())
                .build();
    }

    private me.golemcore.bot.proto.session.v1.TraceRecord toProtoTrace(TraceRecord traceRecord) {
        me.golemcore.bot.proto.session.v1.TraceRecord.Builder builder = me.golemcore.bot.proto.session.v1.TraceRecord
                .newBuilder()
                .setTruncated(traceRecord.isTruncated())
                .setCompressedSnapshotBytes(safeLong(traceRecord.getCompressedSnapshotBytes()))
                .setUncompressedSnapshotBytes(safeLong(traceRecord.getUncompressedSnapshotBytes()));
        putIfNotBlank(traceRecord.getTraceId(), builder::setTraceId);
        putIfNotBlank(traceRecord.getRootSpanId(), builder::setRootSpanId);
        putIfNotBlank(traceRecord.getTraceName(), builder::setTraceName);
        if (traceRecord.getStartedAt() != null) {
            builder.setStartedAt(toTimestamp(traceRecord.getStartedAt()));
        }
        if (traceRecord.getEndedAt() != null) {
            builder.setEndedAt(toTimestamp(traceRecord.getEndedAt()));
        }
        if (traceRecord.getSpans() != null) {
            for (TraceSpanRecord spanRecord : traceRecord.getSpans()) {
                if (spanRecord != null) {
                    builder.addSpans(toProtoSpan(spanRecord));
                }
            }
        }
        return builder.build();
    }

    private List<TraceRecord> fromProtoTraces(List<me.golemcore.bot.proto.session.v1.TraceRecord> traces) {
        List<TraceRecord> results = new ArrayList<>();
        for (me.golemcore.bot.proto.session.v1.TraceRecord trace : traces) {
            results.add(fromProtoTrace(trace));
        }
        return results;
    }

    private TraceRecord fromProtoTrace(me.golemcore.bot.proto.session.v1.TraceRecord traceRecord) {
        return TraceRecord.builder()
                .traceId(blankToNull(traceRecord.getTraceId()))
                .rootSpanId(blankToNull(traceRecord.getRootSpanId()))
                .traceName(blankToNull(traceRecord.getTraceName()))
                .startedAt(traceRecord.hasStartedAt() ? fromTimestamp(traceRecord.getStartedAt()) : null)
                .endedAt(traceRecord.hasEndedAt() ? fromTimestamp(traceRecord.getEndedAt()) : null)
                .spans(fromProtoSpans(traceRecord.getSpansList()))
                .truncated(traceRecord.getTruncated())
                .compressedSnapshotBytes(traceRecord.getCompressedSnapshotBytes())
                .uncompressedSnapshotBytes(traceRecord.getUncompressedSnapshotBytes())
                .build();
    }

    private me.golemcore.bot.proto.session.v1.SpanRecord toProtoSpan(TraceSpanRecord spanRecord) {
        me.golemcore.bot.proto.session.v1.SpanRecord.Builder builder = me.golemcore.bot.proto.session.v1.SpanRecord
                .newBuilder()
                .setKind(toProtoTraceSpanKind(spanRecord.getKind()))
                .setStatusCode(toProtoTraceStatusCode(spanRecord.getStatusCode()));
        putIfNotBlank(spanRecord.getSpanId(), builder::setSpanId);
        putIfNotBlank(spanRecord.getParentSpanId(), builder::setParentSpanId);
        putIfNotBlank(spanRecord.getName(), builder::setName);
        putIfNotBlank(spanRecord.getStatusMessage(), builder::setStatusMessage);
        if (spanRecord.getStartedAt() != null) {
            builder.setStartedAt(toTimestamp(spanRecord.getStartedAt()));
        }
        if (spanRecord.getEndedAt() != null) {
            builder.setEndedAt(toTimestamp(spanRecord.getEndedAt()));
        }
        if (spanRecord.getAttributes() != null) {
            for (Map.Entry<String, Object> entry : spanRecord.getAttributes().entrySet()) {
                if (entry.getKey() != null) {
                    builder.putAttributes(entry.getKey(), toJsonValue(entry.getValue()));
                }
            }
        }
        if (spanRecord.getEvents() != null) {
            for (TraceEventRecord eventRecord : spanRecord.getEvents()) {
                if (eventRecord != null) {
                    builder.addEvents(toProtoTraceEvent(eventRecord));
                }
            }
        }
        if (spanRecord.getSnapshots() != null) {
            for (TraceSnapshot snapshot : spanRecord.getSnapshots()) {
                if (snapshot != null) {
                    builder.addSnapshots(toProtoTraceSnapshot(snapshot));
                }
            }
        }
        return builder.build();
    }

    private List<TraceSpanRecord> fromProtoSpans(List<me.golemcore.bot.proto.session.v1.SpanRecord> spans) {
        List<TraceSpanRecord> results = new ArrayList<>();
        for (me.golemcore.bot.proto.session.v1.SpanRecord span : spans) {
            results.add(fromProtoSpan(span));
        }
        return results;
    }

    private TraceSpanRecord fromProtoSpan(me.golemcore.bot.proto.session.v1.SpanRecord spanRecord) {
        return TraceSpanRecord.builder()
                .spanId(blankToNull(spanRecord.getSpanId()))
                .parentSpanId(blankToNull(spanRecord.getParentSpanId()))
                .name(blankToNull(spanRecord.getName()))
                .kind(fromProtoTraceSpanKind(spanRecord.getKind()))
                .statusCode(fromProtoTraceStatusCode(spanRecord.getStatusCode()))
                .statusMessage(blankToNull(spanRecord.getStatusMessage()))
                .startedAt(spanRecord.hasStartedAt() ? fromTimestamp(spanRecord.getStartedAt()) : null)
                .endedAt(spanRecord.hasEndedAt() ? fromTimestamp(spanRecord.getEndedAt()) : null)
                .attributes(fromProtoMap(spanRecord.getAttributesMap()))
                .events(fromProtoTraceEvents(spanRecord.getEventsList()))
                .snapshots(fromProtoTraceSnapshots(spanRecord.getSnapshotsList()))
                .build();
    }

    private me.golemcore.bot.proto.session.v1.SpanEventRecord toProtoTraceEvent(TraceEventRecord eventRecord) {
        me.golemcore.bot.proto.session.v1.SpanEventRecord.Builder builder = me.golemcore.bot.proto.session.v1.SpanEventRecord
                .newBuilder();
        putIfNotBlank(eventRecord.getName(), builder::setName);
        if (eventRecord.getTimestamp() != null) {
            builder.setTimestamp(toTimestamp(eventRecord.getTimestamp()));
        }
        if (eventRecord.getAttributes() != null) {
            for (Map.Entry<String, Object> entry : eventRecord.getAttributes().entrySet()) {
                if (entry.getKey() != null) {
                    builder.putAttributes(entry.getKey(), toJsonValue(entry.getValue()));
                }
            }
        }
        return builder.build();
    }

    private List<TraceEventRecord> fromProtoTraceEvents(
            List<me.golemcore.bot.proto.session.v1.SpanEventRecord> events) {
        List<TraceEventRecord> results = new ArrayList<>();
        for (me.golemcore.bot.proto.session.v1.SpanEventRecord event : events) {
            results.add(TraceEventRecord.builder()
                    .name(blankToNull(event.getName()))
                    .timestamp(event.hasTimestamp() ? fromTimestamp(event.getTimestamp()) : null)
                    .attributes(fromProtoMap(event.getAttributesMap()))
                    .build());
        }
        return results;
    }

    private me.golemcore.bot.proto.session.v1.SnapshotRecord toProtoTraceSnapshot(TraceSnapshot snapshot) {
        me.golemcore.bot.proto.session.v1.SnapshotRecord.Builder builder = me.golemcore.bot.proto.session.v1.SnapshotRecord
                .newBuilder()
                .setTruncated(snapshot.isTruncated())
                .setOriginalSize(safeLong(snapshot.getOriginalSize()))
                .setCompressedSize(safeLong(snapshot.getCompressedSize()));
        putIfNotBlank(snapshot.getSnapshotId(), builder::setSnapshotId);
        putIfNotBlank(snapshot.getRole(), builder::setRole);
        putIfNotBlank(snapshot.getContentType(), builder::setContentType);
        putIfNotBlank(snapshot.getEncoding(), builder::setEncoding);
        if (snapshot.getCompressedPayload() != null && snapshot.getCompressedPayload().length > 0) {
            builder.setCompressedPayload(ByteString.copyFrom(snapshot.getCompressedPayload()));
        }
        return builder.build();
    }

    private List<TraceSnapshot> fromProtoTraceSnapshots(
            List<me.golemcore.bot.proto.session.v1.SnapshotRecord> snapshots) {
        List<TraceSnapshot> results = new ArrayList<>();
        for (me.golemcore.bot.proto.session.v1.SnapshotRecord snapshot : snapshots) {
            results.add(TraceSnapshot.builder()
                    .snapshotId(blankToNull(snapshot.getSnapshotId()))
                    .role(blankToNull(snapshot.getRole()))
                    .contentType(blankToNull(snapshot.getContentType()))
                    .encoding(blankToNull(snapshot.getEncoding()))
                    .compressedPayload(snapshot.getCompressedPayload().toByteArray())
                    .originalSize(snapshot.getOriginalSize())
                    .compressedSize(snapshot.getCompressedSize())
                    .truncated(snapshot.getTruncated())
                    .build());
        }
        return results;
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
            if (bigInteger.bitLength() <= LONG_BIT_LENGTH) {
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

    private me.golemcore.bot.proto.session.v1.TraceSpanKind toProtoTraceSpanKind(TraceSpanKind kind) {
        if (kind == null) {
            return me.golemcore.bot.proto.session.v1.TraceSpanKind.TRACE_SPAN_KIND_UNSPECIFIED;
        }
        return switch (kind) {
        case INGRESS -> me.golemcore.bot.proto.session.v1.TraceSpanKind.TRACE_SPAN_KIND_INGRESS;
        case INTERNAL -> me.golemcore.bot.proto.session.v1.TraceSpanKind.TRACE_SPAN_KIND_INTERNAL;
        case LLM -> me.golemcore.bot.proto.session.v1.TraceSpanKind.TRACE_SPAN_KIND_LLM;
        case TOOL -> me.golemcore.bot.proto.session.v1.TraceSpanKind.TRACE_SPAN_KIND_TOOL;
        case STORAGE -> me.golemcore.bot.proto.session.v1.TraceSpanKind.TRACE_SPAN_KIND_STORAGE;
        case OUTBOUND -> me.golemcore.bot.proto.session.v1.TraceSpanKind.TRACE_SPAN_KIND_OUTBOUND;
        };
    }

    private TraceSpanKind fromProtoTraceSpanKind(me.golemcore.bot.proto.session.v1.TraceSpanKind kind) {
        return switch (kind) {
        case TRACE_SPAN_KIND_INGRESS -> TraceSpanKind.INGRESS;
        case TRACE_SPAN_KIND_INTERNAL -> TraceSpanKind.INTERNAL;
        case TRACE_SPAN_KIND_LLM -> TraceSpanKind.LLM;
        case TRACE_SPAN_KIND_TOOL -> TraceSpanKind.TOOL;
        case TRACE_SPAN_KIND_STORAGE -> TraceSpanKind.STORAGE;
        case TRACE_SPAN_KIND_OUTBOUND -> TraceSpanKind.OUTBOUND;
        case TRACE_SPAN_KIND_UNSPECIFIED, UNRECOGNIZED -> null;
        };
    }

    private me.golemcore.bot.proto.session.v1.TraceStatusCode toProtoTraceStatusCode(TraceStatusCode statusCode) {
        if (statusCode == null) {
            return me.golemcore.bot.proto.session.v1.TraceStatusCode.TRACE_STATUS_CODE_UNSPECIFIED;
        }
        return switch (statusCode) {
        case OK -> me.golemcore.bot.proto.session.v1.TraceStatusCode.TRACE_STATUS_CODE_OK;
        case ERROR -> me.golemcore.bot.proto.session.v1.TraceStatusCode.TRACE_STATUS_CODE_ERROR;
        };
    }

    private TraceStatusCode fromProtoTraceStatusCode(me.golemcore.bot.proto.session.v1.TraceStatusCode statusCode) {
        return switch (statusCode) {
        case TRACE_STATUS_CODE_OK -> TraceStatusCode.OK;
        case TRACE_STATUS_CODE_ERROR -> TraceStatusCode.ERROR;
        case TRACE_STATUS_CODE_UNSPECIFIED, UNRECOGNIZED -> null;
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

    private long safeLong(Long value) {
        return value != null ? value : 0L;
    }

    private int safeInt(Integer value) {
        return value != null ? value : 0;
    }
}
