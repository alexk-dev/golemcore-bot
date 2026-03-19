package me.golemcore.bot.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import me.golemcore.bot.adapter.outbound.hive.HiveEventBatchPublisher;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.HiveControlCommandEnvelope;
import me.golemcore.bot.domain.model.Message;

class HiveControlCommandDispatcherTest {

    @Test
    void shouldEnqueueHiveInboundMessageAndAcknowledgeCommand() {
        SessionRunCoordinator coordinator = mock(SessionRunCoordinator.class);
        HiveEventBatchPublisher publisher = mock(HiveEventBatchPublisher.class);
        HiveControlCommandDispatcher dispatcher = new HiveControlCommandDispatcher(
                coordinator,
                publisher,
                Clock.fixed(Instant.parse("2026-03-18T00:00:00Z"), ZoneOffset.UTC));

        HiveControlCommandEnvelope envelope = HiveControlCommandEnvelope.builder()
                .commandId("cmd-1")
                .threadId("thread-1")
                .cardId("card-1")
                .runId("run-1")
                .golemId("golem-1")
                .body("Inspect the repo state")
                .createdAt(Instant.parse("2026-03-18T00:00:01Z"))
                .build();

        dispatcher.dispatch(envelope);

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(coordinator).enqueue(captor.capture());
        verify(publisher).publishCommandAcknowledged(envelope);

        Message inbound = captor.getValue();
        assertEquals("hive", inbound.getChannelType());
        assertEquals("thread-1", inbound.getChatId());
        assertEquals("Inspect the repo state", inbound.getContent());
        assertEquals("thread-1", inbound.getMetadata().get(ContextAttributes.TRANSPORT_CHAT_ID));
        assertEquals("thread-1", inbound.getMetadata().get(ContextAttributes.CONVERSATION_KEY));
        assertEquals("card-1", inbound.getMetadata().get(ContextAttributes.HIVE_CARD_ID));
        assertEquals("cmd-1", inbound.getMetadata().get(ContextAttributes.HIVE_COMMAND_ID));
        assertEquals("run-1", inbound.getMetadata().get(ContextAttributes.HIVE_RUN_ID));
        assertEquals("golem-1", inbound.getMetadata().get(ContextAttributes.HIVE_GOLEM_ID));
    }

    @Test
    void shouldRequestStopForStopControlEvent() {
        SessionRunCoordinator coordinator = mock(SessionRunCoordinator.class);
        HiveEventBatchPublisher publisher = mock(HiveEventBatchPublisher.class);
        HiveControlCommandDispatcher dispatcher = new HiveControlCommandDispatcher(
                coordinator,
                publisher,
                Clock.fixed(Instant.parse("2026-03-18T00:00:00Z"), ZoneOffset.UTC));

        HiveControlCommandEnvelope envelope = HiveControlCommandEnvelope.builder()
                .eventType("command.stop")
                .commandId("cmd-2")
                .threadId("thread-1")
                .runId("run-1")
                .golemId("golem-1")
                .createdAt(Instant.parse("2026-03-18T00:00:02Z"))
                .build();

        dispatcher.dispatch(envelope);

        verify(coordinator).requestStop("hive", "thread-1", "run-1", "cmd-2");
        verify(publisher).publishCommandAcknowledged(envelope);
    }
}
