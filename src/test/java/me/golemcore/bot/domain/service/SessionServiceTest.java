package me.golemcore.bot.domain.service;

import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.port.outbound.StoragePort;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
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
    private static final String SESSION_FILE = "telegram:123.json";
    private static final String NOT_FOUND = "not found";
    private static final String ROLE_USER = "user";
    private static final String NONEXISTENT = "nonexistent";
    private static final String MSG_PREFIX = "msg";
    private static final String SUMMARY_TEXT = "[Summary]";

    private StoragePort storagePort;
    private ObjectMapper objectMapper;
    private Clock clock;
    private SessionService service;

    @BeforeEach
    void setUp() {
        storagePort = mock(StoragePort.class);
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        clock = Clock.fixed(FIXED_TIME, ZoneOffset.UTC);
        service = new SessionService(storagePort, objectMapper, clock);

        when(storagePort.putText(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(storagePort.deleteObject(anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
    }

    // ==================== getOrCreate ====================

    @Test
    void getOrCreateCreatesNewSession() {
        when(storagePort.getText(SESSIONS_DIR, SESSION_FILE))
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
        when(storagePort.getText(SESSIONS_DIR, SESSION_FILE))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException(NOT_FOUND)));

        AgentSession first = service.getOrCreate(CHANNEL_TELEGRAM, CHAT_ID);
        AgentSession second = service.getOrCreate(CHANNEL_TELEGRAM, CHAT_ID);

        assertSame(first, second);
    }

    @Test
    void getOrCreateCreatesNewWhenStorageLoadFails() {
        // Corrupt JSON in storage — service should gracefully create new session
        when(storagePort.getText(SESSIONS_DIR, "telegram:200.json"))
                .thenReturn(CompletableFuture.completedFuture("{corrupt json}"));

        AgentSession session = service.getOrCreate(CHANNEL_TELEGRAM, "200");

        assertNotNull(session);
        assertEquals("telegram:200", session.getId());
        assertEquals(FIXED_TIME, session.getCreatedAt());
    }

    // ==================== get ====================

    @Test
    void getReturnsEmptyForUnknownSession() {
        when(storagePort.getText(SESSIONS_DIR, "unknown.json"))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException(NOT_FOUND)));

        Optional<AgentSession> result = service.get("unknown");

        assertTrue(result.isEmpty());
    }

    @Test
    void getReturnsCachedSession() {
        when(storagePort.getText(SESSIONS_DIR, SESSION_FILE))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException(NOT_FOUND)));

        service.getOrCreate(CHANNEL_TELEGRAM, CHAT_ID);
        Optional<AgentSession> result = service.get(SESSION_ID);

        assertTrue(result.isPresent());
    }

    @Test
    void getReturnsEmptyWhenStorageJsonIsCorrupt() {
        when(storagePort.getText(SESSIONS_DIR, "telegram:456.json"))
                .thenReturn(CompletableFuture.completedFuture("{corrupt}"));

        Optional<AgentSession> result = service.get("telegram:456");

        assertTrue(result.isEmpty());
    }

    @Test
    void getReturnsEmptyForBlankJson() {
        when(storagePort.getText(SESSIONS_DIR, "blank.json"))
                .thenReturn(CompletableFuture.completedFuture("  "));

        Optional<AgentSession> result = service.get("blank");

        assertTrue(result.isEmpty());
    }

    @Test
    void getReturnsEmptyForNullJson() {
        when(storagePort.getText(SESSIONS_DIR, "nulljson.json"))
                .thenReturn(CompletableFuture.completedFuture(null));

        Optional<AgentSession> result = service.get("nulljson");

        assertTrue(result.isEmpty());
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
        verify(storagePort).putText(eq(SESSIONS_DIR), eq(SESSION_FILE), anyString());
    }

    @Test
    void saveHandlesStorageFailure() {
        when(storagePort.putText(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("disk full")));

        AgentSession session = AgentSession.builder()
                .id(SESSION_ID)
                .channelType(CHANNEL_TELEGRAM)
                .chatId(CHAT_ID)
                .build();

        assertDoesNotThrow(() -> service.save(session));
    }

    // ==================== delete ====================

    @Test
    void deleteRemovesFromCacheAndStorage() {
        when(storagePort.getText(SESSIONS_DIR, SESSION_FILE))
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

    // ==================== clearMessages ====================

    @Test
    void clearMessagesRemovesAllMessages() {
        when(storagePort.getText(SESSIONS_DIR, SESSION_FILE))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException(NOT_FOUND)));

        AgentSession session = service.getOrCreate(CHANNEL_TELEGRAM, CHAT_ID);
        session.addMessage(Message.builder().role(ROLE_USER).content("hi").timestamp(Instant.now()).build());
        assertEquals(1, session.getMessages().size());

        service.clearMessages(SESSION_ID);

        assertEquals(0, session.getMessages().size());
        verify(storagePort).putText(eq(SESSIONS_DIR), eq(SESSION_FILE), anyString());
    }

    @Test
    void clearMessagesDoesNothingForUnknownSession() {
        service.clearMessages(NONEXISTENT);
        verify(storagePort, never()).putText(anyString(), anyString(), anyString());
    }

    // ==================== compactMessages ====================

    @Test
    void compactMessagesRemovesOldMessages() {
        when(storagePort.getText(SESSIONS_DIR, SESSION_FILE))
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
        when(storagePort.getText(SESSIONS_DIR, SESSION_FILE))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException(NOT_FOUND)));

        AgentSession session = service.getOrCreate(CHANNEL_TELEGRAM, CHAT_ID);
        session.addMessage(Message.builder().role(ROLE_USER).content(MSG_PREFIX).timestamp(Instant.now()).build());

        int removed = service.compactMessages(SESSION_ID, 5);

        assertEquals(0, removed);
    }

    @Test
    void compactMessagesKeepLastEqualsTotal() {
        when(storagePort.getText(SESSIONS_DIR, SESSION_FILE))
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
        when(storagePort.getText(SESSIONS_DIR, SESSION_FILE))
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
        when(storagePort.getText(SESSIONS_DIR, SESSION_FILE))
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
        when(storagePort.getText(SESSIONS_DIR, SESSION_FILE))
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
        when(storagePort.getText(SESSIONS_DIR, SESSION_FILE))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException(NOT_FOUND)));

        AgentSession session = service.getOrCreate(CHANNEL_TELEGRAM, CHAT_ID);
        session.addMessage(Message.builder().role(ROLE_USER).content(MSG_PREFIX).timestamp(Instant.now()).build());

        assertTrue(service.getMessagesToCompact(SESSION_ID, 5).isEmpty());
    }

    // ==================== listAll ====================

    @Test
    void listAllReturnsCachedSessions() {
        when(storagePort.getText(SESSIONS_DIR, SESSION_FILE))
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
                .thenReturn(CompletableFuture.completedFuture(List.of("web:999.json")));
        when(storagePort.getText(SESSIONS_DIR, "web:999.json"))
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

    // ==================== getMessageCount ====================

    @Test
    void getMessageCountReturnsZeroForUnknown() {
        assertEquals(0, service.getMessageCount(NONEXISTENT));
    }

    @Test
    void getMessageCountReturnsCorrectCount() {
        when(storagePort.getText(SESSIONS_DIR, SESSION_FILE))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException(NOT_FOUND)));

        AgentSession session = service.getOrCreate(CHANNEL_TELEGRAM, CHAT_ID);
        session.addMessage(Message.builder().role(ROLE_USER).content("a").timestamp(Instant.now()).build());
        session.addMessage(Message.builder().role("assistant").content("b").timestamp(Instant.now()).build());

        assertEquals(2, service.getMessageCount(SESSION_ID));
    }
}
