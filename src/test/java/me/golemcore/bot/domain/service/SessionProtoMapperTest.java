package me.golemcore.bot.domain.service;

import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.AudioFormat;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.proto.session.v1.AgentSessionRecord;
import me.golemcore.bot.proto.session.v1.JsonValue;
import me.golemcore.bot.proto.session.v1.SessionState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SuppressWarnings("PMD.TooManyMethods")
class SessionProtoMapperTest {

    private SessionProtoMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new SessionProtoMapper();
    }

    // ── Round-trip: full session ──────────────────────────────────────

    @Test
    void shouldRoundTripFullSession() {
        Instant now = Instant.parse("2026-02-16T10:30:00.123456789Z");
        Message message = Message.builder()
                .id("msg-1")
                .role("user")
                .content("Hello")
                .channelType("telegram")
                .chatId("chat-42")
                .senderId("user-7")
                .timestamp(now)
                .build();

        AgentSession session = AgentSession.builder()
                .id("sess-1")
                .channelType("telegram")
                .chatId("chat-42")
                .messages(new ArrayList<>(List.of(message)))
                .metadata(new LinkedHashMap<>(Map.of("key", "value")))
                .state(AgentSession.SessionState.ACTIVE)
                .createdAt(now)
                .updatedAt(now.plusSeconds(60))
                .build();

        AgentSessionRecord proto = mapper.toProto(session);
        AgentSession restored = mapper.fromProto(proto);

        assertEquals("sess-1", restored.getId());
        assertEquals("telegram", restored.getChannelType());
        assertEquals("chat-42", restored.getChatId());
        assertEquals(AgentSession.SessionState.ACTIVE, restored.getState());
        assertEquals(now, restored.getCreatedAt());
        assertEquals(now.plusSeconds(60), restored.getUpdatedAt());
        assertEquals(1, restored.getMessages().size());
        assertEquals("Hello", restored.getMessages().get(0).getContent());
        assertEquals("value", restored.getMetadata().get("key"));
    }

    @Test
    void shouldRoundTripMinimalSession() {
        AgentSession session = AgentSession.builder()
                .messages(new ArrayList<>())
                .metadata(new LinkedHashMap<>())
                .build();

        AgentSessionRecord proto = mapper.toProto(session);
        AgentSession restored = mapper.fromProto(proto);

        assertNull(restored.getId());
        assertNull(restored.getChannelType());
        assertNull(restored.getChatId());
        assertNull(restored.getCreatedAt());
        assertNull(restored.getUpdatedAt());
        assertTrue(restored.getMessages().isEmpty());
        assertTrue(restored.getMetadata().isEmpty());
    }

    // ── Blank/null string handling ────────────────────────────────────

    @Test
    void shouldConvertBlankStringsToNull() {
        AgentSession session = AgentSession.builder()
                .id("   ")
                .channelType("")
                .chatId(null)
                .messages(new ArrayList<>())
                .metadata(new LinkedHashMap<>())
                .build();

        AgentSessionRecord proto = mapper.toProto(session);
        AgentSession restored = mapper.fromProto(proto);

        assertNull(restored.getId());
        assertNull(restored.getChannelType());
        assertNull(restored.getChatId());
    }

    @Test
    void shouldPreserveNonBlankStrings() {
        AgentSession session = AgentSession.builder()
                .id("valid-id")
                .channelType("web")
                .chatId("chat-1")
                .messages(new ArrayList<>())
                .metadata(new LinkedHashMap<>())
                .build();

        AgentSessionRecord proto = mapper.toProto(session);
        AgentSession restored = mapper.fromProto(proto);

        assertEquals("valid-id", restored.getId());
        assertEquals("web", restored.getChannelType());
        assertEquals("chat-1", restored.getChatId());
    }

    // ── Timestamp nanosecond precision ────────────────────────────────

    @Test
    void shouldPreserveNanosecondPrecision() {
        Instant precise = Instant.ofEpochSecond(1708000000L, 123456789);
        AgentSession session = AgentSession.builder()
                .messages(new ArrayList<>())
                .metadata(new LinkedHashMap<>())
                .createdAt(precise)
                .build();

        AgentSessionRecord proto = mapper.toProto(session);
        AgentSession restored = mapper.fromProto(proto);

        assertEquals(precise, restored.getCreatedAt());
        assertEquals(123456789, restored.getCreatedAt().getNano());
    }

    // ── SessionState enum mappings ────────────────────────────────────

    @ParameterizedTest
    @EnumSource(AgentSession.SessionState.class)
    void shouldRoundTripAllSessionStates(AgentSession.SessionState state) {
        AgentSession session = AgentSession.builder()
                .messages(new ArrayList<>())
                .metadata(new LinkedHashMap<>())
                .state(state)
                .build();

        AgentSessionRecord proto = mapper.toProto(session);
        AgentSession restored = mapper.fromProto(proto);

        assertEquals(state, restored.getState());
    }

    @Test
    void shouldMapUnspecifiedSessionStateToActive() {
        AgentSessionRecord proto = AgentSessionRecord.newBuilder()
                .setState(SessionState.SESSION_STATE_UNSPECIFIED)
                .build();

        AgentSession restored = mapper.fromProto(proto);

        assertEquals(AgentSession.SessionState.ACTIVE, restored.getState());
    }

    // ── AudioFormat enum mappings ─────────────────────────────────────

    @ParameterizedTest
    @EnumSource(AudioFormat.class)
    void shouldRoundTripAllAudioFormats(AudioFormat format) {
        Message message = Message.builder()
                .role("user")
                .audioFormat(format)
                .voiceData(new byte[] { 1, 2, 3 })
                .build();

        AgentSession session = AgentSession.builder()
                .messages(new ArrayList<>(List.of(message)))
                .metadata(new LinkedHashMap<>())
                .build();

        AgentSessionRecord proto = mapper.toProto(session);
        AgentSession restored = mapper.fromProto(proto);

        assertEquals(format, restored.getMessages().get(0).getAudioFormat());
    }

    @Test
    void shouldMapUnspecifiedAudioFormatToNull() {
        AgentSessionRecord proto = AgentSessionRecord.newBuilder()
                .addMessages(me.golemcore.bot.proto.session.v1.MessageRecord.newBuilder()
                        .setRole("user")
                        .setAudioFormat(me.golemcore.bot.proto.session.v1.AudioFormat.AUDIO_FORMAT_UNSPECIFIED)
                        .build())
                .build();

        AgentSession restored = mapper.fromProto(proto);

        assertNull(restored.getMessages().get(0).getAudioFormat());
    }

    // ── JSON value type serialization ─────────────────────────────────

    @Test
    void shouldRoundTripBooleanMetadata() {
        AgentSession session = sessionWithMetadata(Map.of("flag", true));

        AgentSession restored = roundTrip(session);

        assertEquals(true, restored.getMetadata().get("flag"));
    }

    @Test
    void shouldRoundTripIntegerMetadata() {
        AgentSession session = sessionWithMetadata(Map.of("count", 42));

        AgentSession restored = roundTrip(session);

        assertEquals(42L, restored.getMetadata().get("count"));
    }

    @Test
    void shouldRoundTripLongMetadata() {
        AgentSession session = sessionWithMetadata(Map.of("bigCount", 9999999999L));

        AgentSession restored = roundTrip(session);

        assertEquals(9999999999L, restored.getMetadata().get("bigCount"));
    }

    @Test
    void shouldRoundTripBigIntegerFittingInLong() {
        BigInteger fitsInLong = BigInteger.valueOf(123456789L);
        AgentSession session = sessionWithMetadata(Map.of("bigInt", fitsInLong));

        AgentSession restored = roundTrip(session);

        assertEquals(123456789L, restored.getMetadata().get("bigInt"));
    }

    @Test
    void shouldSerializeLargeBigIntegerAsString() {
        BigInteger large = BigInteger.ONE.shiftLeft(64);
        AgentSession session = sessionWithMetadata(Map.of("huge", large));

        AgentSessionRecord proto = mapper.toProto(session);
        JsonValue value = proto.getMetadataMap().get("huge");

        assertEquals(large.toString(), value.getStringValue());
    }

    @Test
    void shouldRoundTripDoubleMetadata() {
        AgentSession session = sessionWithMetadata(Map.of("ratio", 3.14));

        AgentSession restored = roundTrip(session);

        assertEquals(3.14, (Double) restored.getMetadata().get("ratio"), 0.001);
    }

    @Test
    void shouldRoundTripBigDecimalAsDouble() {
        BigDecimal decimal = new BigDecimal("2.718");
        AgentSession session = sessionWithMetadata(Map.of("e", decimal));

        AgentSession restored = roundTrip(session);

        assertEquals(2.718, (Double) restored.getMetadata().get("e"), 0.001);
    }

    @Test
    void shouldRoundTripStringMetadata() {
        AgentSession session = sessionWithMetadata(Map.of("name", "test"));

        AgentSession restored = roundTrip(session);

        assertEquals("test", restored.getMetadata().get("name"));
    }

    @Test
    void shouldRoundTripNullMetadataValue() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("empty", null);
        AgentSession session = sessionWithMetadata(meta);

        AgentSession restored = roundTrip(session);

        assertTrue(restored.getMetadata().containsKey("empty"));
        assertNull(restored.getMetadata().get("empty"));
    }

    @Test
    void shouldRoundTripNestedMapMetadata() {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("inner", "value");
        nested.put("num", 5);
        AgentSession session = sessionWithMetadata(Map.of("nested", nested));

        AgentSession restored = roundTrip(session);

        @SuppressWarnings("unchecked")
        Map<String, Object> restoredNested = (Map<String, Object>) restored.getMetadata().get("nested");
        assertNotNull(restoredNested);
        assertEquals("value", restoredNested.get("inner"));
        assertEquals(5L, restoredNested.get("num"));
    }

    @Test
    void shouldRoundTripListMetadata() {
        List<Object> items = List.of("a", 1, true);
        AgentSession session = sessionWithMetadata(Map.of("list", items));

        AgentSession restored = roundTrip(session);

        @SuppressWarnings("unchecked")
        List<Object> restoredList = (List<Object>) restored.getMetadata().get("list");
        assertNotNull(restoredList);
        assertEquals(3, restoredList.size());
        assertEquals("a", restoredList.get(0));
        assertEquals(1L, restoredList.get(1));
        assertEquals(true, restoredList.get(2));
    }

    @Test
    void shouldSerializeUnknownTypeAsString() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("custom", new StringBuilder("fallback"));
        AgentSession session = sessionWithMetadata(meta);

        AgentSessionRecord proto = mapper.toProto(session);
        JsonValue value = proto.getMetadataMap().get("custom");

        assertEquals("fallback", value.getStringValue());
    }

    // ── Messages with tool calls ──────────────────────────────────────

    @Test
    void shouldRoundTripMessageWithToolCalls() {
        Message.ToolCall toolCall = Message.ToolCall.builder()
                .id("call-1")
                .name("search")
                .arguments(new LinkedHashMap<>(Map.of("query", "test")))
                .build();

        Message message = Message.builder()
                .id("msg-1")
                .role("assistant")
                .content("Let me search")
                .toolCalls(new ArrayList<>(List.of(toolCall)))
                .build();

        AgentSession session = AgentSession.builder()
                .messages(new ArrayList<>(List.of(message)))
                .metadata(new LinkedHashMap<>())
                .build();

        AgentSession restored = roundTrip(session);

        Message restoredMsg = restored.getMessages().get(0);
        assertEquals(1, restoredMsg.getToolCalls().size());
        Message.ToolCall restoredCall = restoredMsg.getToolCalls().get(0);
        assertEquals("call-1", restoredCall.getId());
        assertEquals("search", restoredCall.getName());
        assertEquals("test", restoredCall.getArguments().get("query"));
    }

    @Test
    void shouldRoundTripToolResultMessage() {
        Message message = Message.builder()
                .id("msg-2")
                .role("tool")
                .content("Search result: found 5 items")
                .toolCallId("call-1")
                .toolName("search")
                .build();

        AgentSession session = AgentSession.builder()
                .messages(new ArrayList<>(List.of(message)))
                .metadata(new LinkedHashMap<>())
                .build();

        AgentSession restored = roundTrip(session);

        Message restoredMsg = restored.getMessages().get(0);
        assertEquals("tool", restoredMsg.getRole());
        assertEquals("call-1", restoredMsg.getToolCallId());
        assertEquals("search", restoredMsg.getToolName());
    }

    // ── Voice data ────────────────────────────────────────────────────

    @Test
    void shouldRoundTripVoiceMessage() {
        byte[] audio = { 0x00, 0x01, 0x02, (byte) 0xFF };
        Message message = Message.builder()
                .role("user")
                .voiceData(audio)
                .voiceTranscription("Hello world")
                .audioFormat(AudioFormat.OGG_OPUS)
                .build();

        AgentSession session = AgentSession.builder()
                .messages(new ArrayList<>(List.of(message)))
                .metadata(new LinkedHashMap<>())
                .build();

        AgentSession restored = roundTrip(session);

        Message restoredMsg = restored.getMessages().get(0);
        assertArrayEquals(audio, restoredMsg.getVoiceData());
        assertEquals("Hello world", restoredMsg.getVoiceTranscription());
        assertEquals(AudioFormat.OGG_OPUS, restoredMsg.getAudioFormat());
    }

    @Test
    void shouldSkipEmptyVoiceData() {
        Message message = Message.builder()
                .role("user")
                .voiceData(new byte[0])
                .build();

        AgentSession session = AgentSession.builder()
                .messages(new ArrayList<>(List.of(message)))
                .metadata(new LinkedHashMap<>())
                .build();

        AgentSession restored = roundTrip(session);

        assertNull(restored.getMessages().get(0).getVoiceData());
    }

    // ── Message string fields blank handling ──────────────────────────

    @Test
    void shouldConvertBlankMessageFieldsToNull() {
        Message message = Message.builder()
                .id("")
                .role("")
                .content("   ")
                .channelType("")
                .chatId("")
                .senderId("")
                .toolCallId("")
                .toolName("")
                .voiceTranscription("")
                .build();

        AgentSession session = AgentSession.builder()
                .messages(new ArrayList<>(List.of(message)))
                .metadata(new LinkedHashMap<>())
                .build();

        AgentSession restored = roundTrip(session);

        Message restoredMsg = restored.getMessages().get(0);
        assertNull(restoredMsg.getId());
        assertNull(restoredMsg.getRole());
        assertNull(restoredMsg.getContent());
        assertNull(restoredMsg.getChannelType());
        assertNull(restoredMsg.getChatId());
        assertNull(restoredMsg.getSenderId());
        assertNull(restoredMsg.getToolCallId());
        assertNull(restoredMsg.getToolName());
        assertNull(restoredMsg.getVoiceTranscription());
    }

    // ── Multiple messages ─────────────────────────────────────────────

    @Test
    void shouldPreserveMessageOrder() {
        Message msg1 = Message.builder().role("user").content("First").build();
        Message msg2 = Message.builder().role("assistant").content("Second").build();
        Message msg3 = Message.builder().role("user").content("Third").build();

        AgentSession session = AgentSession.builder()
                .messages(new ArrayList<>(List.of(msg1, msg2, msg3)))
                .metadata(new LinkedHashMap<>())
                .build();

        AgentSession restored = roundTrip(session);

        assertEquals(3, restored.getMessages().size());
        assertEquals("First", restored.getMessages().get(0).getContent());
        assertEquals("Second", restored.getMessages().get(1).getContent());
        assertEquals("Third", restored.getMessages().get(2).getContent());
    }

    // ── Metadata with null keys ───────────────────────────────────────

    @Test
    void shouldSkipNullMetadataKeys() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put(null, "ignored");
        meta.put("valid", "kept");
        AgentSession session = sessionWithMetadata(meta);

        AgentSessionRecord proto = mapper.toProto(session);

        assertEquals(1, proto.getMetadataMap().size());
        assertTrue(proto.getMetadataMap().containsKey("valid"));
    }

    // ── Null collections ──────────────────────────────────────────────

    @Test
    void shouldHandleNullMessages() {
        AgentSession session = AgentSession.builder()
                .messages(null)
                .metadata(new LinkedHashMap<>())
                .build();

        AgentSessionRecord proto = mapper.toProto(session);

        assertEquals(0, proto.getMessagesCount());
    }

    @Test
    void shouldHandleNullMetadata() {
        AgentSession session = AgentSession.builder()
                .messages(new ArrayList<>())
                .metadata(null)
                .build();

        AgentSessionRecord proto = mapper.toProto(session);

        assertEquals(0, proto.getMetadataCount());
    }

    @Test
    void shouldHandleNullToolCalls() {
        Message message = Message.builder()
                .role("assistant")
                .content("No tools")
                .toolCalls(null)
                .build();

        AgentSession session = AgentSession.builder()
                .messages(new ArrayList<>(List.of(message)))
                .metadata(new LinkedHashMap<>())
                .build();

        AgentSessionRecord proto = mapper.toProto(session);

        assertEquals(0, proto.getMessages(0).getToolCallsCount());
    }

    // ── Message metadata ──────────────────────────────────────────────

    @Test
    void shouldRoundTripMessageMetadata() {
        Map<String, Object> msgMeta = new LinkedHashMap<>();
        msgMeta.put("model", "gpt-5");
        msgMeta.put("tokens", 150);

        Message message = Message.builder()
                .role("assistant")
                .content("Response")
                .metadata(msgMeta)
                .build();

        AgentSession session = AgentSession.builder()
                .messages(new ArrayList<>(List.of(message)))
                .metadata(new LinkedHashMap<>())
                .build();

        AgentSession restored = roundTrip(session);

        Map<String, Object> restoredMeta = restored.getMessages().get(0).getMetadata();
        assertEquals("gpt-5", restoredMeta.get("model"));
        assertEquals(150L, restoredMeta.get("tokens"));
    }

    // ── Float serialization ───────────────────────────────────────────

    @Test
    void shouldRoundTripFloatAsDouble() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("temperature", 0.7f);
        AgentSession session = sessionWithMetadata(meta);

        AgentSession restored = roundTrip(session);

        double restored_val = (Double) restored.getMetadata().get("temperature");
        assertEquals(0.7, restored_val, 0.01);
    }

    // ── Helper methods ────────────────────────────────────────────────

    private AgentSession sessionWithMetadata(Map<String, Object> metadata) {
        return AgentSession.builder()
                .messages(new ArrayList<>())
                .metadata(new LinkedHashMap<>(metadata))
                .build();
    }

    private AgentSession roundTrip(AgentSession session) {
        AgentSessionRecord proto = mapper.toProto(session);
        return mapper.fromProto(proto);
    }
}
