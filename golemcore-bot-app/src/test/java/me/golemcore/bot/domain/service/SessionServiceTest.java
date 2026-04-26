package me.golemcore.bot.domain.service;

import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.adapter.outbound.storage.ProtoSessionRecordCodecAdapter;
import me.golemcore.bot.port.outbound.StoragePort;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SessionServiceTest {

    private static final Instant FIXED_TIME = Instant.parse("2026-01-15T10:00:00Z");
    private static final String SESSIONS_DIR = "sessions";
    private static final String CHANNEL_TELEGRAM = "telegram";
    private static final String CHAT_ID = "123";
    private static final String SESSION_ID = "telegram:123";
    private static final String SESSION_FILE = "telegram:123.pb";
    private static final String NOT_FOUND = "not found";
    private static final String ROLE_USER = "user";
    private static final String NONEXISTENT = "nonexistent";
    private static final String MSG_PREFIX = "msg";
    private static final String SUMMARY_TEXT = "[Summary]";

    private StoragePort storagePort;
    private ObjectMapper objectMapper;
    private Clock clock;
    private SessionService service;
    private ProtoSessionRecordCodecAdapter sessionRecordCodecAdapter;

    @BeforeEach
    void setUp() {
        storagePort = mock(StoragePort.class);
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        clock = Clock.fixed(FIXED_TIME, ZoneOffset.UTC);
        sessionRecordCodecAdapter = new ProtoSessionRecordCodecAdapter();
        service = new SessionService(storagePort, sessionRecordCodecAdapter, clock, List.of());

        when(storagePort.getObject(anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(storagePort.getText(anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(storagePort.listObjects(anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(List.of()));
        when(storagePort.putObject(anyString(), anyString(), any(byte[].class)))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(storagePort.deleteObject(anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
    }

    // ==================== getOrCreate ====================

    @Test
    void getOrCreateCreatesNewSession() {
        when(storagePort.getObject(SESSIONS_DIR, SESSION_FILE))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException(NOT_FOUND)));

        AgentSession session = service.getOrCreate(CHANNEL_TELEGRAM, CHAT_ID);

        assertNotNull(session);
        assertEquals(SESSION_ID, session.getId());
        assertEquals(CHANNEL_TELEGRAM, session.getChannelType());
        assertEquals(CHAT_ID, session.getChatId());
        assertEquals(FIXED_TIME, session.getCreatedAt());
    }

    @Test
    void getOrCreateReturnsCachedSession() {
        when(storagePort.getObject(SESSIONS_DIR, SESSION_FILE))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException(NOT_FOUND)));

        AgentSession first = service.getOrCreate(CHANNEL_TELEGRAM, CHAT_ID);
        AgentSession second = service.getOrCreate(CHANNEL_TELEGRAM, CHAT_ID);

        assertSame(first, second);
    }

    @Test
    void getOrCreateInheritsModelSettingsFromLatestSameChannelSession() {
        when(storagePort.getObject(SESSIONS_DIR, "telegram:source.pb"))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException(NOT_FOUND)));
        when(storagePort.getObject(SESSIONS_DIR, "telegram:new.pb"))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException(NOT_FOUND)));

        AgentSession source = service.getOrCreate(CHANNEL_TELEGRAM, "source");
        source.getMetadata().put(ContextAttributes.SESSION_MODEL_TIER, "coding");
        source.getMetadata().put(ContextAttributes.SESSION_MODEL_TIER_FORCE, true);

        AgentSession created = service.getOrCreate(CHANNEL_TELEGRAM, "new");

        assertEquals("coding", created.getMetadata().get(ContextAttributes.SESSION_MODEL_TIER));
        assertEquals(true, created.getMetadata().get(ContextAttributes.SESSION_MODEL_TIER_FORCE));
    }

    @Test
    void getOrCreateDoesNotInheritModelSettingsForExcludedChannels() {
        when(storagePort.getObject(SESSIONS_DIR, "webhook:source.pb"))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException(NOT_FOUND)));
        when(storagePort.getObject(SESSIONS_DIR, "webhook:new.pb"))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException(NOT_FOUND)));

        AgentSession source = service.getOrCreate("webhook", "source");
        source.getMetadata().put(ContextAttributes.SESSION_MODEL_TIER, "coding");
        source.getMetadata().put(ContextAttributes.SESSION_MODEL_TIER_FORCE, true);

        AgentSession created = service.getOrCreate("webhook", "new");

        assertFalse(created.getMetadata().containsKey(ContextAttributes.SESSION_MODEL_TIER));
        assertFalse(created.getMetadata().containsKey(ContextAttributes.SESSION_MODEL_TIER_FORCE));
    }

    // ==================== get ====================

    @Test
    void getReturnsEmptyForUnknownSession() {
        when(storagePort.getObject(SESSIONS_DIR, "unknown.pb"))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException(NOT_FOUND)));

        Optional<AgentSession> result = service.get("unknown");

        assertTrue(result.isEmpty());
    }

    @Test
    void getReturnsCachedSession() {
        when(storagePort.getObject(SESSIONS_DIR, SESSION_FILE))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException(NOT_FOUND)));

        service.getOrCreate(CHANNEL_TELEGRAM, CHAT_ID);
        Optional<AgentSession> result = service.get(SESSION_ID);

        assertTrue(result.isPresent());
    }

    @Test
    void getLoadsFromProtobufStorage() {
        AgentSession stored = AgentSession.builder()
                .id(SESSION_ID)
                .channelType(CHANNEL_TELEGRAM)
                .chatId(CHAT_ID)
                .build();
        stored.addMessage(Message.builder()
                .id("m1")
                .role("assistant")
                .content("done")
                .metadata(new LinkedHashMap<>(Map.of("model", "openai/gpt-5.3-builder",
                        "modelTier", "coding")))
                .timestamp(FIXED_TIME)
                .build());
        byte[] payload = sessionRecordCodecAdapter.encode(stored);

        when(storagePort.getObject(SESSIONS_DIR, SESSION_FILE))
                .thenReturn(CompletableFuture.completedFuture(payload));

        Optional<AgentSession> result = service.get(SESSION_ID);

        assertTrue(result.isPresent());
        assertEquals(1, result.get().getMessages().size());
        assertEquals("openai/gpt-5.3-builder", result.get().getMessages().get(0).getMetadata().get("model"));
        assertEquals("coding", result.get().getMessages().get(0).getMetadata().get("modelTier"));
    }

    // ==================== save ====================

    @Test
    void savePersistsToStorage() {
        AgentSession session = AgentSession.builder()
                .id(SESSION_ID)
                .channelType(CHANNEL_TELEGRAM)
                .chatId(CHAT_ID)
                .build();

        service.save(session);

        assertEquals(FIXED_TIME, session.getUpdatedAt());
        verify(storagePort).putObject(eq(SESSIONS_DIR), eq(SESSION_FILE), any(byte[].class));
    }

    @Test
    void saveHandlesStorageFailure() {
        when(storagePort.putObject(anyString(), anyString(), any(byte[].class)))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("disk full")));

        AgentSession session = AgentSession.builder()
                .id(SESSION_ID)
                .channelType(CHANNEL_TELEGRAM)
                .chatId(CHAT_ID)
                .build();

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> service.save(session));
        assertTrue(error.getMessage().contains(SESSION_ID));
    }

    @Test
    void getOrCreateThrowsWhenStoredSessionIsCorrupted() {
        when(storagePort.getObject(SESSIONS_DIR, SESSION_FILE))
                .thenReturn(CompletableFuture.completedFuture(new byte[] { 1, 2, 3, 4 }));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.getOrCreate(CHANNEL_TELEGRAM, CHAT_ID));

        assertTrue(error.getMessage().contains(SESSION_ID));
    }

    // ==================== delete ====================

    @Test
    void deleteRemovesFromCacheAndStorage() {
        when(storagePort.getObject(SESSIONS_DIR, SESSION_FILE))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException(NOT_FOUND)));

        service.getOrCreate(CHANNEL_TELEGRAM, CHAT_ID);
        service.delete(SESSION_ID);

        verify(storagePort).deleteObject(SESSIONS_DIR, SESSION_FILE);
    }

    @Test
    void deleteHandlesStorageFailure() {
        when(storagePort.deleteObject(anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("error")));

        assertDoesNotThrow(() -> service.delete(NONEXISTENT));
    }

    @Test
    void deleteRestoresCachedSessionWhenStorageDeleteFails() {
        when(storagePort.getObject(SESSIONS_DIR, SESSION_FILE))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException(NOT_FOUND)));
        when(storagePort.deleteObject(SESSIONS_DIR, SESSION_FILE))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("error")));

        AgentSession session = service.getOrCreate(CHANNEL_TELEGRAM, CHAT_ID);

        service.delete(SESSION_ID);

        assertTrue(service.get(SESSION_ID).isPresent());
        assertSame(session, service.get(SESSION_ID).orElseThrow());
    }

    // ==================== clearMessages ====================

    @Test
    void clearMessagesRemovesAllMessages() {
        when(storagePort.getObject(SESSIONS_DIR, SESSION_FILE))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException(NOT_FOUND)));

        AgentSession session = service.getOrCreate(CHANNEL_TELEGRAM, CHAT_ID);
        session.addMessage(Message.builder().role(ROLE_USER).content("hi").timestamp(Instant.now()).build());
        assertEquals(1, session.getMessages().size());

        service.clearMessages(SESSION_ID);

        assertEquals(0, session.getMessages().size());
        verify(storagePort).putObject(eq(SESSIONS_DIR), eq(SESSION_FILE), any(byte[].class));
    }

    @Test
    void clearMessagesDoesNothingForUnknownSession() {
        service.clearMessages(NONEXISTENT);
        verify(storagePort, never()).putObject(anyString(), anyString(), any(byte[].class));
    }

    // ==================== compactMessages ====================

    @Test
    void compactMessagesRemovesOldMessages() {
        when(storagePort.getObject(SESSIONS_DIR, SESSION_FILE))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException(NOT_FOUND)));

        AgentSession session = service.getOrCreate(CHANNEL_TELEGRAM, CHAT_ID);
        for (int i = 0; i < 10; i++) {
            session.addMessage(
                    Message.builder().role(ROLE_USER).content(MSG_PREFIX + i).timestamp(Instant.now()).build());
        }

        int removed = service.compactMessages(SESSION_ID, 3);

        assertEquals(7, removed);
        assertEquals(3, session.getMessages().size());
        assertEquals("msg7", session.getMessages().get(0).getContent());
    }

    @Test
    void compactMessagesReturnsMinusOneForUnknownSession() {
        int result = service.compactMessages(NONEXISTENT, 5);
        assertEquals(-1, result);
    }

    @Test
    void compactMessagesReturnsZeroWhenNotEnoughMessages() {
        when(storagePort.getObject(SESSIONS_DIR, SESSION_FILE))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException(NOT_FOUND)));

        AgentSession session = service.getOrCreate(CHANNEL_TELEGRAM, CHAT_ID);
        session.addMessage(Message.builder().role(ROLE_USER).content(MSG_PREFIX).timestamp(Instant.now()).build());

        int removed = service.compactMessages(SESSION_ID, 5);

        assertEquals(0, removed);
    }

    @Test
    void compactMessagesKeepLastEqualsTotal() {
        when(storagePort.getObject(SESSIONS_DIR, SESSION_FILE))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException(NOT_FOUND)));

        AgentSession session = service.getOrCreate(CHANNEL_TELEGRAM, CHAT_ID);
        for (int i = 0; i < 5; i++) {
            session.addMessage(
                    Message.builder().role(ROLE_USER).content(MSG_PREFIX + i).timestamp(Instant.now()).build());
        }

        int removed = service.compactMessages(SESSION_ID, 5);

        assertEquals(0, removed);
        assertEquals(5, session.getMessages().size());
    }

    // ==================== compactWithSummary ====================

    @Test
    void compactWithSummaryReplaceOldMessagesWithSummary() {
        when(storagePort.getObject(SESSIONS_DIR, SESSION_FILE))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException(NOT_FOUND)));

        AgentSession session = service.getOrCreate(CHANNEL_TELEGRAM, CHAT_ID);
        for (int i = 0; i < 10; i++) {
            session.addMessage(
                    Message.builder().role(ROLE_USER).content(MSG_PREFIX + i).timestamp(Instant.now()).build());
        }

        Message summary = Message.builder().role("system").content(SUMMARY_TEXT).timestamp(Instant.now()).build();
        int removed = service.compactWithSummary(SESSION_ID, 3, summary);

        assertEquals(7, removed);
        assertEquals(4, session.getMessages().size()); // summary + 3 kept
        assertEquals(SUMMARY_TEXT, session.getMessages().get(0).getContent());
        assertEquals("msg7", session.getMessages().get(1).getContent());
    }

    @Test
    void compactWithSummaryReturnsMinusOneForUnknownSession() {
        Message summary = Message.builder().role("system").content(SUMMARY_TEXT).timestamp(Instant.now()).build();
        assertEquals(-1, service.compactWithSummary(NONEXISTENT, 3, summary));
    }

    @Test
    void compactWithSummaryReturnsZeroWhenNotEnough() {
        when(storagePort.getObject(SESSIONS_DIR, SESSION_FILE))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException(NOT_FOUND)));

        AgentSession session = service.getOrCreate(CHANNEL_TELEGRAM, CHAT_ID);
        session.addMessage(Message.builder().role(ROLE_USER).content(MSG_PREFIX).timestamp(Instant.now()).build());

        Message summary = Message.builder().role("system").content(SUMMARY_TEXT).timestamp(Instant.now()).build();
        int removed = service.compactWithSummary(SESSION_ID, 5, summary);

        assertEquals(0, removed);
    }

    // ==================== getMessagesToCompact ====================

    @Test
    void getMessagesToCompactReturnsOldMessages() {
        when(storagePort.getObject(SESSIONS_DIR, SESSION_FILE))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException(NOT_FOUND)));

        AgentSession session = service.getOrCreate(CHANNEL_TELEGRAM, CHAT_ID);
        for (int i = 0; i < 10; i++) {
            session.addMessage(
                    Message.builder().role(ROLE_USER).content(MSG_PREFIX + i).timestamp(Instant.now()).build());
        }

        List<Message> toCompact = service.getMessagesToCompact(SESSION_ID, 3);

        assertEquals(7, toCompact.size());
        assertEquals("msg0", toCompact.get(0).getContent());
        assertEquals("msg6", toCompact.get(6).getContent());
    }

    @Test
    void getMessagesToCompactReturnsEmptyForUnknown() {
        assertTrue(service.getMessagesToCompact(NONEXISTENT, 5).isEmpty());
    }

    @Test
    void getMessagesToCompactReturnsEmptyWhenNotEnough() {
        when(storagePort.getObject(SESSIONS_DIR, SESSION_FILE))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException(NOT_FOUND)));

        AgentSession session = service.getOrCreate(CHANNEL_TELEGRAM, CHAT_ID);
        session.addMessage(Message.builder().role(ROLE_USER).content(MSG_PREFIX).timestamp(Instant.now()).build());

        assertTrue(service.getMessagesToCompact(SESSION_ID, 5).isEmpty());
    }

    // ==================== listAll ====================

    @Test
    void listAllReturnsCachedSessions() {
        when(storagePort.getObject(SESSIONS_DIR, SESSION_FILE))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException(NOT_FOUND)));
        when(storagePort.listObjects(anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(List.of()));

        service.getOrCreate(CHANNEL_TELEGRAM, CHAT_ID);
        List<AgentSession> all = service.listAll();

        assertFalse(all.isEmpty());
        assertEquals(SESSION_ID, all.get(0).getId());
    }

    @Test
    void listAllScansStorageDirectory() {
        when(storagePort.listObjects(anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(List.of("web:999.pb")));
        when(storagePort.getObject(SESSIONS_DIR, "web:999.pb"))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("not found")));

        List<AgentSession> all = service.listAll();

        // Verifies listObjects was called to scan storage
        verify(storagePort).listObjects(SESSIONS_DIR, "");
        assertNotNull(all);
    }

    @Test
    void listAllHandlesStorageScanFailure() {
        when(storagePort.listObjects(anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("scan error")));

        List<AgentSession> all = service.listAll();
        assertNotNull(all);
    }

    @Test
    void listByChannelTypeReturnsOnlyMatchingSessions() {
        when(storagePort.getObject(SESSIONS_DIR, "web:web-chat.pb"))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException(NOT_FOUND)));
        when(storagePort.getObject(SESSIONS_DIR, "telegram:tg-chat.pb"))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException(NOT_FOUND)));

        service.getOrCreate("web", "web-chat");
        service.getOrCreate("telegram", "tg-chat");

        List<AgentSession> webSessions = service.listByChannelType("web");

        assertEquals(1, webSessions.size());
        assertEquals("web:web-chat", webSessions.get(0).getId());
    }

    @Test
    void listByChannelTypeAndTransportReturnsOnlyOwnedSessions() {
        when(storagePort.getObject(SESSIONS_DIR, "telegram:conv-1.pb"))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException(NOT_FOUND)));
        when(storagePort.getObject(SESSIONS_DIR, "telegram:conv-2.pb"))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException(NOT_FOUND)));

        AgentSession conv1 = service.getOrCreate("telegram", "conv-1");
        AgentSession conv2 = service.getOrCreate("telegram", "conv-2");
        SessionIdentitySupport.bindTransportAndConversation(conv1, "100", "conv-1");
        SessionIdentitySupport.bindTransportAndConversation(conv2, "200", "conv-2");
        service.save(conv1);
        service.save(conv2);

        List<AgentSession> owned = service.listByChannelTypeAndTransportChatId("telegram", "100");

        assertEquals(1, owned.size());
        assertEquals("telegram:conv-1", owned.get(0).getId());
    }

    @Test
    void cleanupExpiredSessionsDeletesOnlySessionsOlderThanCutoff() {
        when(storagePort.getObject(SESSIONS_DIR, "telegram:old.pb"))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException(NOT_FOUND)));
        when(storagePort.getObject(SESSIONS_DIR, "telegram:fresh.pb"))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException(NOT_FOUND)));

        AgentSession oldSession = service.getOrCreate(CHANNEL_TELEGRAM, "old");
        oldSession.setUpdatedAt(FIXED_TIME.minusSeconds(3_600));
        AgentSession freshSession = service.getOrCreate(CHANNEL_TELEGRAM, "fresh");
        freshSession.setUpdatedAt(FIXED_TIME.plusSeconds(3_600));

        int deleted = service.cleanupExpiredSessions(FIXED_TIME, session -> false);

        assertEquals(1, deleted);
        verify(storagePort).deleteObject(SESSIONS_DIR, "telegram:old.pb");
        verify(storagePort, never()).deleteObject(SESSIONS_DIR, "telegram:fresh.pb");
    }

    @Test
    void cleanupExpiredSessionsRetainsSessionsMatchingPredicate() {
        when(storagePort.getObject(SESSIONS_DIR, "telegram:old.pb"))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException(NOT_FOUND)));

        AgentSession oldSession = service.getOrCreate(CHANNEL_TELEGRAM, "old");
        oldSession.setUpdatedAt(FIXED_TIME.minusSeconds(3_600));

        int deleted = service.cleanupExpiredSessions(FIXED_TIME, session -> "telegram:old".equals(session.getId()));

        assertEquals(0, deleted);
        verify(storagePort, never()).deleteObject(SESSIONS_DIR, "telegram:old.pb");
    }

    @Test
    void cleanupExpiredSessionsRejectsNullShouldRetainPredicate() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.cleanupExpiredSessions(FIXED_TIME, null));

        assertTrue(error.getMessage().contains("shouldRetain"),
                "error should name the missing argument so misuse is obvious");
        verify(storagePort, never()).deleteObject(anyString(), anyString());
    }

    @Test
    void cleanupExpiredSessionsDoesNotCountDeletionFailures() {
        when(storagePort.getObject(SESSIONS_DIR, "telegram:old.pb"))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException(NOT_FOUND)));
        when(storagePort.deleteObject(SESSIONS_DIR, "telegram:old.pb"))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("error")));

        AgentSession oldSession = service.getOrCreate(CHANNEL_TELEGRAM, "old");
        oldSession.setUpdatedAt(FIXED_TIME.minusSeconds(3_600));

        int deleted = service.cleanupExpiredSessions(FIXED_TIME, session -> false);

        assertEquals(0, deleted);
        assertTrue(service.get("telegram:old").isPresent());
    }

    // ==================== getMessageCount ====================

    @Test
    void getMessageCountReturnsZeroForUnknown() {
        assertEquals(0, service.getMessageCount(NONEXISTENT));
    }

    @Test
    void getMessageCountReturnsCorrectCount() {
        when(storagePort.getObject(SESSIONS_DIR, SESSION_FILE))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException(NOT_FOUND)));

        AgentSession session = service.getOrCreate(CHANNEL_TELEGRAM, CHAT_ID);
        session.addMessage(Message.builder().role(ROLE_USER).content("a").timestamp(Instant.now()).build());
        session.addMessage(Message.builder().role("assistant").content("b").timestamp(Instant.now()).build());

        assertEquals(2, service.getMessageCount(SESSION_ID));
    }

    // ==================== Message Jackson round-trip ====================

    @Test
    void shouldRoundTripMessageWithToolFieldsViaJackson() throws Exception {
        Message original = Message.builder()
                .id("m1")
                .role("assistant")
                .toolCalls(List.of(Message.ToolCall.builder()
                        .id("call_1")
                        .name("shell")
                        .arguments(Map.of("command", "ls"))
                        .build()))
                .timestamp(FIXED_TIME)
                .build();

        String json = objectMapper.writeValueAsString(original);
        Message deserialized = objectMapper.readValue(json, Message.class);

        assertEquals(original.getId(), deserialized.getId());
        assertEquals(original.getRole(), deserialized.getRole());
        assertNotNull(deserialized.getToolCalls());
        assertEquals(1, deserialized.getToolCalls().size());
        assertEquals("call_1", deserialized.getToolCalls().get(0).getId());
        assertEquals("shell", deserialized.getToolCalls().get(0).getName());
        assertEquals("ls", deserialized.getToolCalls().get(0).getArguments().get("command"));
    }

    @Test
    void shouldRoundTripToolResultMessageViaJackson() throws Exception {
        Message original = Message.builder()
                .id("m2")
                .role("tool")
                .content("file1.txt")
                .toolCallId("call_1")
                .toolName("shell")
                .timestamp(FIXED_TIME)
                .build();

        String json = objectMapper.writeValueAsString(original);
        Message deserialized = objectMapper.readValue(json, Message.class);

        assertEquals("tool", deserialized.getRole());
        assertEquals("call_1", deserialized.getToolCallId());
        assertEquals("shell", deserialized.getToolName());
        assertEquals("file1.txt", deserialized.getContent());
    }
}
