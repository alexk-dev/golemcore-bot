package me.golemcore.bot.domain.system;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import me.golemcore.bot.adapter.outbound.hive.HiveEventBatchPublisher;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.RuntimeEvent;
import me.golemcore.bot.domain.model.RuntimeEventType;
import org.junit.jupiter.api.Test;

class HiveRuntimeEventDispatchSystemTest {

    @Test
    void shouldDispatchHiveRuntimeEventsWithTurnMetadata() {
        HiveEventBatchPublisher publisher = mock(HiveEventBatchPublisher.class);
        HiveRuntimeEventDispatchSystem system = new HiveRuntimeEventDispatchSystem(publisher);
        AgentContext context = AgentContext.builder()
                .session(AgentSession.builder()
                        .id("hive:thread-1")
                        .channelType("hive")
                        .chatId("thread-1")
                        .messages(new ArrayList<>())
                        .build())
                .messages(new ArrayList<>())
                .build();
        context.setAttribute(ContextAttributes.RUNTIME_EVENTS, List.of(RuntimeEvent.builder()
                .type(RuntimeEventType.TURN_STARTED)
                .timestamp(Instant.parse("2026-03-18T00:00:00Z"))
                .sessionId("hive:thread-1")
                .channelType("hive")
                .chatId("thread-1")
                .payload(Map.of())
                .build()));
        context.setAttribute(ContextAttributes.HIVE_THREAD_ID, "thread-1");
        context.setAttribute(ContextAttributes.HIVE_CARD_ID, "card-1");
        context.setAttribute(ContextAttributes.HIVE_COMMAND_ID, "cmd-1");
        context.setAttribute(ContextAttributes.HIVE_RUN_ID, "run-1");

        system.process(context);

        verify(publisher).publishRuntimeEvents(anyList(),
                argThat(metadata -> "thread-1".equals(metadata.get(ContextAttributes.HIVE_THREAD_ID))
                        && "card-1".equals(metadata.get(ContextAttributes.HIVE_CARD_ID))
                        && "cmd-1".equals(metadata.get(ContextAttributes.HIVE_COMMAND_ID))
                        && "run-1".equals(metadata.get(ContextAttributes.HIVE_RUN_ID))));
    }

    @Test
    void shouldSkipNonHiveSessions() {
        HiveEventBatchPublisher publisher = mock(HiveEventBatchPublisher.class);
        HiveRuntimeEventDispatchSystem system = new HiveRuntimeEventDispatchSystem(publisher);
        AgentContext context = AgentContext.builder()
                .session(AgentSession.builder()
                        .id("web:chat-1")
                        .channelType("web")
                        .chatId("chat-1")
                        .messages(new ArrayList<>())
                        .build())
                .messages(new ArrayList<>())
                .build();

        system.process(context);

        verifyNoInteractions(publisher);
    }
}
