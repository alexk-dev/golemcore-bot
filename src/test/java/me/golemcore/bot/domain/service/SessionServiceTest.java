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
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SessionServiceTest {

    private StoragePort storagePort;
    private ObjectMapper objectMapper;
    private Clock clock;
    private SessionService service;

    private static final Instant FIXED_TIME = Instant.parse("2026-01-15T10:00:00Z");

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
        when(storagePort.getText("sessions", "telegram:123.json"))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("not found")));

        AgentSession session = service.getOrCreate("telegram", "123");

        assertNotNull(session);
        assertEquals("telegram:123", session.getId());
        assertEquals("telegram", session.getChannelType());
        assertEquals("123", session.getChatId());
        assertEquals(FIXED_TIME, session.getCreatedAt());
    }

    @Test
    void getOrCreateReturnsCachedSession() {
        when(storagePort.getText("sessions", "telegram:123.json"))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("not found")));

        AgentSession first = service.getOrCreate("telegram", "123");
        AgentSession second = service.getOrCreate("telegram", "123");

        assertSame(first, second);
    }

    @Test
    void getOrCreateCreatesNewWhenStorageLoadFails() {
        // Corrupt JSON in storage — service should gracefully create new session
        when(storagePort.getText("sessions", "telegram:200.json"))
                .thenReturn(CompletableFuture.completedFuture("{corrupt json}"));

        AgentSession session = service.getOrCreate("telegram", "200");

        assertNotNull(session);
        assertEquals("telegram:200", session.getId());
        assertEquals(FIXED_TIME, session.getCreatedAt());
    }

    // ==================== get ====================

    @Test
    void getReturnsEmptyForUnknownSession() {
        when(storagePort.getText("sessions", "unknown.json"))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("not found")));

        Optional<AgentSession> result = service.get("unknown");

        assertTrue(result.isEmpty());
    }

    @Test
    void getReturnsCachedSession() {
        when(storagePort.getText("sessions", "telegram:123.json"))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("not found")));

        service.getOrCreate("telegram", "123");
        Optional<AgentSession> result = service.get("telegram:123");

        assertTrue(result.isPresent());
    }

    @Test
    void getReturnsEmptyWhenStorageJsonIsCorrupt() {
        when(storagePort.getText("sessions", "telegram:456.json"))
                .thenReturn(CompletableFuture.completedFuture("{corrupt}"));

        Optional<AgentSession> result = service.get("telegram:456");

        assertTrue(result.isEmpty());
    }

    @Test
    void getReturnsEmptyForBlankJson() {
        when(storagePort.getText("sessions", "blank.json"))
                .thenReturn(CompletableFuture.completedFuture("  "));

        Optional<AgentSession> result = service.get("blank");

        assertTrue(result.isEmpty());
    }

    @Test
    void getReturnsEmptyForNullJson() {
        when(storagePort.getText("sessions", "nulljson.json"))
                .thenReturn(CompletableFuture.completedFuture(null));

        Optional<AgentSession> result = service.get("nulljson");

        assertTrue(result.isEmpty());
    }

    // ==================== save ====================

    @Test
    void savePersistsToStorage() {
        AgentSession session = AgentSession.builder()
                .id("telegram:123")
                .channelType("telegram")
                .chatId("123")
                .build();

        service.save(session);

        assertEquals(FIXED_TIME, session.getUpdatedAt());
        verify(storagePort).putText(eq("sessions"), eq("telegram:123.json"), anyString());
    }

    @Test
    void saveHandlesStorageFailure() {
        when(storagePort.putText(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("disk full")));

        AgentSession session = AgentSession.builder()
                .id("telegram:123")
                .channelType("telegram")
                .chatId("123")
                .build();

        assertDoesNotThrow(() -> service.save(session));
    }

    // ==================== delete ====================

    @Test
    void deleteRemovesFromCacheAndStorage() {
        when(storagePort.getText("sessions", "telegram:123.json"))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("not found")));

        service.getOrCreate("telegram", "123");
        service.delete("telegram:123");

        verify(storagePort).deleteObject("sessions", "telegram:123.json");
    }

    @Test
    void deleteHandlesStorageFailure() {
        when(storagePort.deleteObject(anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("error")));

        assertDoesNotThrow(() -> service.delete("nonexistent"));
    }

    // ==================== clearMessages ====================

    @Test
    void clearMessagesRemovesAllMessages() {
        when(storagePort.getText("sessions", "telegram:123.json"))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("not found")));

        AgentSession session = service.getOrCreate("telegram", "123");
        session.addMessage(Message.builder().role("user").content("hi").timestamp(Instant.now()).build());
        assertEquals(1, session.getMessages().size());

        service.clearMessages("telegram:123");

        assertEquals(0, session.getMessages().size());
        verify(storagePort).putText(eq("sessions"), eq("telegram:123.json"), anyString());
    }

    @Test
    void clearMessagesDoesNothingForUnknownSession() {
        service.clearMessages("nonexistent");
        verify(storagePort, never()).putText(anyString(), anyString(), anyString());
    }

    // ==================== compactMessages ====================

    @Test
    void compactMessagesRemovesOldMessages() {
        when(storagePort.getText("sessions", "telegram:123.json"))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("not found")));

        AgentSession session = service.getOrCreate("telegram", "123");
        for (int i = 0; i < 10; i++) {
            session.addMessage(Message.builder().role("user").content("msg" + i).timestamp(Instant.now()).build());
        }

        int removed = service.compactMessages("telegram:123", 3);

        assertEquals(7, removed);
        assertEquals(3, session.getMessages().size());
        assertEquals("msg7", session.getMessages().get(0).getContent());
    }

    @Test
    void compactMessagesReturnsMinusOneForUnknownSession() {
        int result = service.compactMessages("nonexistent", 5);
        assertEquals(-1, result);
    }

    @Test
    void compactMessagesReturnsZeroWhenNotEnoughMessages() {
        when(storagePort.getText("sessions", "telegram:123.json"))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("not found")));

        AgentSession session = service.getOrCreate("telegram", "123");
        session.addMessage(Message.builder().role("user").content("msg").timestamp(Instant.now()).build());

        int removed = service.compactMessages("telegram:123", 5);

        assertEquals(0, removed);
    }

    @Test
    void compactMessagesKeepLastEqualsTotal() {
        when(storagePort.getText("sessions", "telegram:123.json"))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("not found")));

        AgentSession session = service.getOrCreate("telegram", "123");
        for (int i = 0; i < 5; i++) {
            session.addMessage(Message.builder().role("user").content("msg" + i).timestamp(Instant.now()).build());
        }

        int removed = service.compactMessages("telegram:123", 5);

        assertEquals(0, removed);
        assertEquals(5, session.getMessages().size());
    }

    // ==================== compactWithSummary ====================

    @Test
    void compactWithSummaryReplaceOldMessagesWithSummary() {
        when(storagePort.getText("sessions", "telegram:123.json"))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("not found")));

        AgentSession session = service.getOrCreate("telegram", "123");
        for (int i = 0; i < 10; i++) {
            session.addMessage(Message.builder().role("user").content("msg" + i).timestamp(Instant.now()).build());
        }

        Message summary = Message.builder().role("system").content("[Summary]").timestamp(Instant.now()).build();
        int removed = service.compactWithSummary("telegram:123", 3, summary);

        assertEquals(7, removed);
        assertEquals(4, session.getMessages().size()); // summary + 3 kept
        assertEquals("[Summary]", session.getMessages().get(0).getContent());
        assertEquals("msg7", session.getMessages().get(1).getContent());
    }

    @Test
    void compactWithSummaryReturnsMinusOneForUnknownSession() {
        Message summary = Message.builder().role("system").content("[Summary]").timestamp(Instant.now()).build();
        assertEquals(-1, service.compactWithSummary("nonexistent", 3, summary));
    }

    @Test
    void compactWithSummaryReturnsZeroWhenNotEnough() {
        when(storagePort.getText("sessions", "telegram:123.json"))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("not found")));

        AgentSession session = service.getOrCreate("telegram", "123");
        session.addMessage(Message.builder().role("user").content("msg").timestamp(Instant.now()).build());

        Message summary = Message.builder().role("system").content("[Summary]").timestamp(Instant.now()).build();
        int removed = service.compactWithSummary("telegram:123", 5, summary);

        assertEquals(0, removed);
    }

    // ==================== getMessagesToCompact ====================

    @Test
    void getMessagesToCompactReturnsOldMessages() {
        when(storagePort.getText("sessions", "telegram:123.json"))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("not found")));

        AgentSession session = service.getOrCreate("telegram", "123");
        for (int i = 0; i < 10; i++) {
            session.addMessage(Message.builder().role("user").content("msg" + i).timestamp(Instant.now()).build());
        }

        List<Message> toCompact = service.getMessagesToCompact("telegram:123", 3);

        assertEquals(7, toCompact.size());
        assertEquals("msg0", toCompact.get(0).getContent());
        assertEquals("msg6", toCompact.get(6).getContent());
    }

    @Test
    void getMessagesToCompactReturnsEmptyForUnknown() {
        assertTrue(service.getMessagesToCompact("nonexistent", 5).isEmpty());
    }

    @Test
    void getMessagesToCompactReturnsEmptyWhenNotEnough() {
        when(storagePort.getText("sessions", "telegram:123.json"))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("not found")));

        AgentSession session = service.getOrCreate("telegram", "123");
        session.addMessage(Message.builder().role("user").content("msg").timestamp(Instant.now()).build());

        assertTrue(service.getMessagesToCompact("telegram:123", 5).isEmpty());
    }

    // ==================== getMessageCount ====================

    @Test
    void getMessageCountReturnsZeroForUnknown() {
        assertEquals(0, service.getMessageCount("nonexistent"));
    }

    @Test
    void getMessageCountReturnsCorrectCount() {
        when(storagePort.getText("sessions", "telegram:123.json"))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("not found")));

        AgentSession session = service.getOrCreate("telegram", "123");
        session.addMessage(Message.builder().role("user").content("a").timestamp(Instant.now()).build());
        session.addMessage(Message.builder().role("assistant").content("b").timestamp(Instant.now()).build());

        assertEquals(2, service.getMessageCount("telegram:123"));
    }
}
