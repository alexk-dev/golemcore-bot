package me.golemcore.bot.domain.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class AgentSessionTest {

    @Test
    void shouldAddMessageWhenHistoryListIsImmutable() {
        AgentSession session = AgentSession.builder()
                .messages(List.of(Message.builder()
                        .role("assistant")
                        .content("existing")
                        .build()))
                .build();

        session.addMessage(Message.builder()
                .role("user")
                .content("incoming")
                .build());

        assertEquals(2, session.getMessages().size());
        assertEquals("incoming", session.getMessages().get(1).getContent());
        assertNotNull(session.getUpdatedAt());
    }

    @Test
    void shouldExposeMutableMessagesWhenHistoryListIsImmutable() {
        AgentSession session = AgentSession.builder()
                .messages(List.of())
                .build();

        session.mutableMessages().add(Message.builder()
                .role("user")
                .content("incoming")
                .build());

        assertEquals(1, session.getMessages().size());
        assertEquals("incoming", session.getMessages().getFirst().getContent());
    }
}
