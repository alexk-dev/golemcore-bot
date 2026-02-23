package me.golemcore.bot.domain.service;

import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.ContextAttributes;
import org.junit.jupiter.api.Test;

import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionIdentitySupportTest {

    @Test
    void shouldResolveConversationKeyFromMetadata() {
        AgentSession session = AgentSession.builder()
                .id("telegram:legacy-chat")
                .channelType("telegram")
                .chatId("legacy-chat")
                .metadata(new HashMap<>())
                .build();
        session.getMetadata().put(ContextAttributes.CONVERSATION_KEY, "conv-meta");

        String conversationKey = SessionIdentitySupport.resolveConversationKey(session);

        assertEquals("conv-meta", conversationKey);
    }

    @Test
    void shouldResolveConversationKeyFromSessionIdWhenMetadataMissing() {
        AgentSession session = AgentSession.builder()
                .id("telegram:conv-from-id")
                .channelType("telegram")
                .chatId("legacy-chat")
                .build();

        String conversationKey = SessionIdentitySupport.resolveConversationKey(session);

        assertEquals("conv-from-id", conversationKey);
    }

    @Test
    void shouldFallbackConversationKeyToChatIdWhenSessionIdWithoutSeparator() {
        AgentSession session = AgentSession.builder()
                .id("telegram")
                .channelType("telegram")
                .chatId("legacy-chat")
                .build();

        String conversationKey = SessionIdentitySupport.resolveConversationKey(session);

        assertEquals("legacy-chat", conversationKey);
    }

    @Test
    void shouldResolveTransportChatIdFromMetadata() {
        AgentSession session = AgentSession.builder()
                .id("telegram:conv-1")
                .channelType("telegram")
                .chatId("conv-1")
                .metadata(new HashMap<>())
                .build();
        session.getMetadata().put(ContextAttributes.TRANSPORT_CHAT_ID, "transport-100");

        String transportChatId = SessionIdentitySupport.resolveTransportChatId(session);

        assertEquals("transport-100", transportChatId);
    }

    @Test
    void shouldBindTransportAndConversation() {
        AgentSession session = AgentSession.builder()
                .id("telegram:conv-1")
                .channelType("telegram")
                .chatId("conv-1")
                .metadata(new HashMap<>())
                .build();

        boolean changed = SessionIdentitySupport.bindTransportAndConversation(session, "transport-100", "conv-1");

        assertTrue(changed);
        assertEquals("transport-100", session.getMetadata().get(ContextAttributes.TRANSPORT_CHAT_ID));
        assertEquals("conv-1", session.getMetadata().get(ContextAttributes.CONVERSATION_KEY));
    }

    @Test
    void shouldNotChangeWhenBindingSameValues() {
        AgentSession session = AgentSession.builder()
                .id("telegram:conv-1")
                .channelType("telegram")
                .chatId("conv-1")
                .metadata(new HashMap<>())
                .build();
        session.getMetadata().put(ContextAttributes.TRANSPORT_CHAT_ID, "transport-100");
        session.getMetadata().put(ContextAttributes.CONVERSATION_KEY, "conv-1");

        boolean changed = SessionIdentitySupport.bindTransportAndConversation(session, "transport-100", "conv-1");

        assertFalse(changed);
    }

    @Test
    void shouldCreateMetadataWhenBindingIntoLegacySession() {
        AgentSession session = AgentSession.builder()
                .id("telegram:conv-legacy")
                .channelType("telegram")
                .chatId("conv-legacy")
                .build();

        boolean changed = SessionIdentitySupport.bindTransportAndConversation(session, "transport-500", "conv-legacy");

        assertTrue(changed);
        assertEquals("transport-500", session.getMetadata().get(ContextAttributes.TRANSPORT_CHAT_ID));
        assertEquals("conv-legacy", session.getMetadata().get(ContextAttributes.CONVERSATION_KEY));
    }

    @Test
    void shouldReadMetadataStringWithTrimAndIgnoreNonStringValues() {
        AgentSession session = AgentSession.builder()
                .id("telegram:conv-1")
                .channelType("telegram")
                .chatId("conv-1")
                .metadata(new HashMap<>())
                .build();
        session.getMetadata().put("key1", "  value-1  ");
        session.getMetadata().put("key2", 42);
        session.getMetadata().put("key3", "   ");

        assertEquals("value-1", SessionIdentitySupport.readMetadataString(session, "key1"));
        assertNull(SessionIdentitySupport.readMetadataString(session, "key2"));
        assertNull(SessionIdentitySupport.readMetadataString(session, "key3"));
    }

    @Test
    void shouldResolveTransportMembershipFromMetadata() {
        AgentSession session = AgentSession.builder()
                .id("telegram:conv-1")
                .channelType("telegram")
                .chatId("conv-1")
                .metadata(new HashMap<>())
                .build();
        session.getMetadata().put(ContextAttributes.TRANSPORT_CHAT_ID, "transport-100");

        assertTrue(SessionIdentitySupport.belongsToTransport(session, "transport-100"));
        assertFalse(SessionIdentitySupport.belongsToTransport(session, "transport-200"));
        assertFalse(SessionIdentitySupport.belongsToTransport(session, " "));
    }
}
