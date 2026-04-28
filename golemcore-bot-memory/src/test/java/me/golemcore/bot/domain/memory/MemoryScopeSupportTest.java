package me.golemcore.bot.domain.memory;

import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.AutoRunKind;
import me.golemcore.bot.domain.model.ContextAttributes;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
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
        AgentSession session = AgentSession.builder().id("web:legacy").channelType("web").chatId("legacy")
                .metadata(new HashMap<>(Map.of(ContextAttributes.CONVERSATION_KEY, "conv-123"))).build();

        String scope = MemoryScopeSupport.resolveScopeFromSessionOrGlobal(session);

        assertEquals("session:web:conv-123", scope);
    }

    @Test
    void shouldFallbackConversationKeyToChatIdWhenMetadataAndSessionIdMissing() {
        AgentSession session = AgentSession.builder().id("web").channelType("web").chatId("conv-from-chat").build();

        String scope = MemoryScopeSupport.resolveScopeFromSessionOrGlobal(session);

        assertEquals("session:web:conv-from-chat", scope);
    }

    @Test
    void shouldResolveGlobalScopeWhenChannelTypeInvalid() {
        AgentSession session = AgentSession.builder().id("web:conv-1").channelType("web/mobile").chatId("conv-1")
                .build();

        String scope = MemoryScopeSupport.resolveScopeFromSessionOrGlobal(session);

        assertEquals(MemoryScopeSupport.GLOBAL_SCOPE, scope);
    }

    @Test
    void shouldNormalizeScopePrefixesCaseInsensitively() {
        assertEquals("session:web:Conv_123", MemoryScopeSupport.normalizeScopeOrGlobal(" SESSION:WEB:Conv_123 "));
        assertEquals("goal:web:Conv_123:Goal-1",
                MemoryScopeSupport.normalizeScopeOrGlobal(" GOAL:WEB:Conv_123:Goal-1 "));
        assertEquals("task:Task-1", MemoryScopeSupport.normalizeScopeOrGlobal(" TASK:Task-1 "));
    }

    @Test
    void shouldBuildScopeChainFromCaseInsensitiveSessionScope() {
        List<String> chain = MemoryScopeSupport.buildScopeChain(" SESSION:WEB:Conv_123 ",
                AutoRunKind.GOAL_RUN.name().toLowerCase(Locale.ROOT), "Goal-1", "Task-1");

        assertEquals(List.of("task:Task-1", "goal:web:Conv_123:Goal-1", "session:web:Conv_123", "global"), chain);
    }
}
