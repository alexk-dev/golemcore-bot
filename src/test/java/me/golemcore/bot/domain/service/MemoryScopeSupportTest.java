package me.golemcore.bot.domain.service;

import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.ContextAttributes;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MemoryScopeSupportTest {

    @Test
    void shouldResolveGlobalScopeWhenSessionMissing() {
        String scope = MemoryScopeSupport.resolveScopeFromSessionOrGlobal(null);

        assertEquals(MemoryScopeSupport.GLOBAL_SCOPE, scope);
    }

    @Test
    void shouldResolveSessionScopeFromMetadataConversationKey() {
        AgentSession session = AgentSession.builder()
                .id("web:legacy")
                .channelType("web")
                .chatId("legacy")
                .metadata(new HashMap<>(Map.of(ContextAttributes.CONVERSATION_KEY, "conv-123")))
                .build();

        String scope = MemoryScopeSupport.resolveScopeFromSessionOrGlobal(session);

        assertEquals("session:web:conv-123", scope);
    }

    @Test
    void shouldFallbackConversationKeyToChatIdWhenMetadataAndSessionIdMissing() {
        AgentSession session = AgentSession.builder()
                .id("web")
                .channelType("web")
                .chatId("conv-from-chat")
                .build();

        String scope = MemoryScopeSupport.resolveScopeFromSessionOrGlobal(session);

        assertEquals("session:web:conv-from-chat", scope);
    }

    @Test
    void shouldResolveGlobalScopeWhenChannelTypeInvalid() {
        AgentSession session = AgentSession.builder()
                .id("web:conv-1")
                .channelType("web/mobile")
                .chatId("conv-1")
                .build();

        String scope = MemoryScopeSupport.resolveScopeFromSessionOrGlobal(session);

        assertEquals(MemoryScopeSupport.GLOBAL_SCOPE, scope);
    }
}
