package me.golemcore.bot.domain.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CommandExecutionContextTest {

    @Test
    void shouldConvertLegacyMapToTypedContextAndBack() {
        Map<String, Object> legacy = new LinkedHashMap<>();
        legacy.put(CommandExecutionContext.KEY_SESSION_ID, " web:chat-1 ");
        legacy.put(CommandExecutionContext.KEY_CHANNEL_TYPE, " web ");
        legacy.put(CommandExecutionContext.KEY_CHAT_ID, " chat-1 ");
        legacy.put(CommandExecutionContext.KEY_SESSION_CHAT_ID, " session-chat-1 ");
        legacy.put(CommandExecutionContext.KEY_TRANSPORT_CHAT_ID, " transport-chat-1 ");
        legacy.put(CommandExecutionContext.KEY_CONVERSATION_KEY, " conversation-1 ");
        legacy.put(CommandExecutionContext.KEY_ACTOR_ID, " user-1 ");
        legacy.put(CommandExecutionContext.KEY_LOCALE, " en-US ");
        legacy.put("clientMessageId", "msg-1");
        legacy.put("retry", Integer.valueOf(2));

        CommandExecutionContext context = CommandExecutionContext.fromLegacyMap(legacy);

        assertEquals("web:chat-1", context.sessionId());
        assertEquals("web", context.channelType());
        assertEquals("chat-1", context.chatId());
        assertEquals("session-chat-1", context.sessionChatId());
        assertEquals("transport-chat-1", context.transportChatId());
        assertEquals("conversation-1", context.conversationKey());
        assertEquals("user-1", context.actorId());
        assertEquals("en-US", context.locale());
        assertEquals("session-chat-1", context.effectiveSessionChatId());
        assertEquals("transport-chat-1", context.effectiveTransportChatId());
        assertEquals("conversation-1", context.effectiveConversationKey());
        assertTrue(context.hasExplicitSessionRouting());

        Map<String, Object> converted = context.toLegacyMap();
        assertEquals("web:chat-1", converted.get(CommandExecutionContext.KEY_SESSION_ID));
        assertEquals("msg-1", converted.get("clientMessageId"));
        assertEquals(Integer.valueOf(2), converted.get("retry"));
    }

    @Test
    void shouldFallbackToChatIdWhenSpecificRoutingIsAbsent() {
        CommandExecutionContext context = CommandExecutionContext.builder()
                .chatId("chat-1")
                .build();

        assertEquals("chat-1", context.effectiveSessionChatId());
        assertEquals("chat-1", context.effectiveTransportChatId());
        assertEquals("chat-1", context.effectiveConversationKey());
        assertFalse(context.hasExplicitSessionRouting());
        assertNull(context.sessionId());
    }

    @Test
    void shouldIgnoreBlankKeysAndNullMetadataValues() {
        CommandExecutionContext context = CommandExecutionContext.builder()
                .sessionId(" ")
                .metadata(" ", "blank-key")
                .metadata("null-value", null)
                .metadata("valid", "value")
                .build();

        assertNull(context.sessionId());
        assertEquals(Map.of("valid", "value"), context.metadata());
        assertEquals(Map.of("valid", "value"), context.toLegacyMap());
    }
}
