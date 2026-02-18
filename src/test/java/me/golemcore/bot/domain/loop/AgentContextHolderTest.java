package me.golemcore.bot.domain.loop;

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

class AgentContextHolderTest {

    @AfterEach
    void tearDown() {
        AgentContextHolder.clear();
    }

    @Test
    void shouldReturnNullBeforeSet() {
        assertNull(AgentContextHolder.get());
    }

    @Test
    void shouldReturnContextAfterSet() {
        AgentContext context = AgentContext.builder()
                .session(mock(AgentSession.class))
                .messages(new ArrayList<>())
                .build();

        AgentContextHolder.set(context);

        assertEquals(context, AgentContextHolder.get());
    }

    @Test
    void shouldReturnNullAfterClear() {
        AgentContext context = AgentContext.builder()
                .session(mock(AgentSession.class))
                .messages(new ArrayList<>())
                .build();

        AgentContextHolder.set(context);
        AgentContextHolder.clear();

        assertNull(AgentContextHolder.get());
    }

    @Test
    void shouldIsolateBetweenThreads() throws Exception {
        AgentContext context1 = AgentContext.builder()
                .session(mock(AgentSession.class))
                .messages(new ArrayList<>())
                .build();
        AgentContext context2 = AgentContext.builder()
                .session(mock(AgentSession.class))
                .messages(new ArrayList<>())
                .build();

        AgentContextHolder.set(context1);

        AtomicReference<AgentContext> otherThreadContext = new AtomicReference<>();
        Thread otherThread = new Thread(() -> {
            AgentContextHolder.set(context2);
            otherThreadContext.set(AgentContextHolder.get());
            AgentContextHolder.clear();
        });
        otherThread.start();
        otherThread.join();

        assertEquals(context1, AgentContextHolder.get());
        assertEquals(context2, otherThreadContext.get());
    }
}
