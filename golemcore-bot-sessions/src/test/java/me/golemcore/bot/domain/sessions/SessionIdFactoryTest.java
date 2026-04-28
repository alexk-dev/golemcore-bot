package me.golemcore.bot.domain.sessions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.golemcore.bot.domain.model.AgentSession;
import org.junit.jupiter.api.Test;

class SessionIdFactoryTest {

    private final SessionIdFactory factory = new SessionIdFactory();

    @Test
    void shouldBuildSessionIdAndStorageFileName() {
        assertEquals("telegram:123", factory.buildSessionId("telegram", "123"));
        assertEquals("telegram:123.pb", factory.storageFileName("telegram:123"));
    }

    @Test
    void shouldMatchStoredFilesForRequestedChannel() {
        assertTrue(factory.isStoredFileForChannel("sessions/telegram:123.pb", "telegram"));
        assertTrue(factory.isStoredFileForChannel("sessions\\telegram:123.pb", "telegram"));
    }

    @Test
    void shouldRejectNonMatchingStoredFiles() {
        assertFalse(factory.isStoredFileForChannel(null, "telegram"));
        assertFalse(factory.isStoredFileForChannel("sessions/telegram:123.pb", null));
        assertFalse(factory.isStoredFileForChannel("sessions/telegram:123.json", "telegram"));
        assertFalse(factory.isStoredFileForChannel("sessions/:123.pb", "telegram"));
        assertFalse(factory.isStoredFileForChannel("sessions/slack:123.pb", "telegram"));
    }

    @Test
    void shouldEnrichSessionFieldsFromLegacyPath() {
        AgentSession session = AgentSession.builder().build();

        factory.enrichSessionFields(session, "telegram\\\\123.pb");

        assertEquals("telegram:123", session.getId());
        assertEquals("telegram", session.getChannelType());
        assertEquals("123", session.getChatId());
    }

    @Test
    void shouldPreserveExistingSessionFields() {
        AgentSession session = AgentSession.builder().id("web:abc").channelType("custom").chatId("chat").build();

        factory.enrichSessionFields(session, "telegram/123.pb");

        assertEquals("web:abc", session.getId());
        assertEquals("custom", session.getChannelType());
        assertEquals("chat", session.getChatId());
    }
}
