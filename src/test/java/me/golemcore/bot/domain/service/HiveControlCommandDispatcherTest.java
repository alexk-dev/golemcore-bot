package me.golemcore.bot.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import me.golemcore.bot.port.outbound.HiveEventPublishPort;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.HiveControlCommandEnvelope;
import me.golemcore.bot.domain.model.HiveInspectionRequestBody;
import me.golemcore.bot.domain.model.Message;

class HiveControlCommandDispatcherTest {

    @Test
    void shouldEnqueueHiveInboundMessageAndAcknowledgeCommand() {
        SessionRunCoordinator coordinator = mock(SessionRunCoordinator.class);
        HiveControlInboxService inboxService = mock(HiveControlInboxService.class);
        HiveEventPublishPort publisher = mock(HiveEventPublishPort.class);
        HiveInspectionCommandHandler inspectionCommandHandler = mock(HiveInspectionCommandHandler.class);
        HivePolicySyncCommandHandler policySyncCommandHandler = mock(HivePolicySyncCommandHandler.class);
        when(coordinator.submit(any(Message.class), any(Runnable.class))).thenAnswer(invocation -> {
            Runnable onStart = invocation.getArgument(1);
            if (onStart != null) {
                onStart.run();
            }
            return CompletableFuture.completedFuture(null);
        });
        HiveControlCommandDispatcher dispatcher = new HiveControlCommandDispatcher(
                coordinator,
                inboxService,
                publisher,
                inspectionCommandHandler,
                policySyncCommandHandler,
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
        verify(coordinator).submit(captor.capture(), any(Runnable.class));
        verify(inboxService).markProcessed("cmd-1");
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
        HiveControlInboxService inboxService = mock(HiveControlInboxService.class);
        HiveEventPublishPort publisher = mock(HiveEventPublishPort.class);
        HiveInspectionCommandHandler inspectionCommandHandler = mock(HiveInspectionCommandHandler.class);
        HivePolicySyncCommandHandler policySyncCommandHandler = mock(HivePolicySyncCommandHandler.class);
        HiveControlCommandDispatcher dispatcher = new HiveControlCommandDispatcher(
                coordinator,
                inboxService,
                publisher,
                inspectionCommandHandler,
                policySyncCommandHandler,
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

    @Test
    void shouldRouteInspectionRequestToDedicatedHandler() {
        SessionRunCoordinator coordinator = mock(SessionRunCoordinator.class);
        HiveControlInboxService inboxService = mock(HiveControlInboxService.class);
        HiveEventPublishPort publisher = mock(HiveEventPublishPort.class);
        HiveInspectionCommandHandler inspectionCommandHandler = mock(HiveInspectionCommandHandler.class);
        HivePolicySyncCommandHandler policySyncCommandHandler = mock(HivePolicySyncCommandHandler.class);
        HiveControlCommandDispatcher dispatcher = new HiveControlCommandDispatcher(
                coordinator,
                inboxService,
                publisher,
                inspectionCommandHandler,
                policySyncCommandHandler,
                Clock.fixed(Instant.parse("2026-03-18T00:00:00Z"), ZoneOffset.UTC));

        HiveControlCommandEnvelope envelope = HiveControlCommandEnvelope.builder()
                .eventType("inspection.request")
                .requestId("req-1")
                .threadId("thread-1")
                .inspection(HiveInspectionRequestBody.builder()
                        .operation("sessions.list")
                        .channel("web")
                        .build())
                .build();

        dispatcher.dispatch(envelope);

        verify(inspectionCommandHandler).handle(envelope);
        verify(inboxService).markProcessed("req-1");
        verify(coordinator, never()).submit(any(Message.class), any(Runnable.class));
        verify(publisher, never()).publishCommandAcknowledged(envelope);
    }

    @Test
    void shouldRoutePolicySyncRequestToDedicatedHandler() {
        SessionRunCoordinator coordinator = mock(SessionRunCoordinator.class);
        HiveControlInboxService inboxService = mock(HiveControlInboxService.class);
        HiveEventPublishPort publisher = mock(HiveEventPublishPort.class);
        HiveInspectionCommandHandler inspectionCommandHandler = mock(HiveInspectionCommandHandler.class);
        HivePolicySyncCommandHandler policySyncCommandHandler = mock(HivePolicySyncCommandHandler.class);
        HiveControlCommandDispatcher dispatcher = new HiveControlCommandDispatcher(
                coordinator,
                inboxService,
                publisher,
                inspectionCommandHandler,
                policySyncCommandHandler,
                Clock.fixed(Instant.parse("2026-03-18T00:00:00Z"), ZoneOffset.UTC));

        HiveControlCommandEnvelope envelope = HiveControlCommandEnvelope.builder()
                .eventType("policy.sync_requested")
                .commandId("cmd-sync-1")
                .threadId("thread-1")
                .policyGroupId("pg-1")
                .targetVersion(7)
                .checksum("sha256:abcd")
                .build();

        dispatcher.dispatch(envelope);

        verify(policySyncCommandHandler).handle(envelope);
        verify(inboxService).markProcessed("cmd-sync-1");
        verify(coordinator, never()).submit(any(Message.class), any(Runnable.class));
        verify(publisher, never()).publishCommandAcknowledged(envelope);
    }

    @Test
    void shouldMarkPolicySyncRequestFailedWhenHandlerThrows() {
        SessionRunCoordinator coordinator = mock(SessionRunCoordinator.class);
        HiveControlInboxService inboxService = mock(HiveControlInboxService.class);
        HiveEventPublishPort publisher = mock(HiveEventPublishPort.class);
        HiveInspectionCommandHandler inspectionCommandHandler = mock(HiveInspectionCommandHandler.class);
        HivePolicySyncCommandHandler policySyncCommandHandler = mock(HivePolicySyncCommandHandler.class);
        IllegalArgumentException failure = new IllegalArgumentException("policy failed");
        org.mockito.Mockito.doThrow(failure).when(policySyncCommandHandler)
                .handle(any(HiveControlCommandEnvelope.class));
        HiveControlCommandDispatcher dispatcher = new HiveControlCommandDispatcher(
                coordinator,
                inboxService,
                publisher,
                inspectionCommandHandler,
                policySyncCommandHandler,
                Clock.fixed(Instant.parse("2026-03-18T00:00:00Z"), ZoneOffset.UTC));

        HiveControlCommandEnvelope envelope = HiveControlCommandEnvelope.builder()
                .eventType("policy.sync_requested")
                .commandId("cmd-sync-2")
                .threadId("thread-1")
                .policyGroupId("pg-1")
                .targetVersion(8)
                .checksum("sha256:efgh")
                .build();

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> dispatcher.dispatch(envelope));

        assertEquals("Failed to handle Hive policy sync request", error.getMessage());
        assertEquals(failure, error.getCause());
        verify(inboxService).markFailedIfPending("cmd-sync-2", failure);
        verify(publisher, never()).publishCommandAcknowledged(envelope);
    }

    @Test
    void shouldRejectUnsupportedEventType() {
        SessionRunCoordinator coordinator = mock(SessionRunCoordinator.class);
        HiveControlInboxService inboxService = mock(HiveControlInboxService.class);
        HiveEventPublishPort publisher = mock(HiveEventPublishPort.class);
        HiveInspectionCommandHandler inspectionCommandHandler = mock(HiveInspectionCommandHandler.class);
        HivePolicySyncCommandHandler policySyncCommandHandler = mock(HivePolicySyncCommandHandler.class);
        HiveControlCommandDispatcher dispatcher = new HiveControlCommandDispatcher(
                coordinator,
                inboxService,
                publisher,
                inspectionCommandHandler,
                policySyncCommandHandler,
                Clock.fixed(Instant.parse("2026-03-18T00:00:00Z"), ZoneOffset.UTC));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> dispatcher.dispatch(HiveControlCommandEnvelope.builder()
                        .eventType("unknown")
                        .commandId("cmd-1")
                        .threadId("thread-1")
                        .body("hello")
                        .build()));

        assertEquals("Unsupported Hive control command eventType: unknown", error.getMessage());
    }

    @Test
    void shouldMarkInspectionRequestFailedWhenHandlerThrows() {
        SessionRunCoordinator coordinator = mock(SessionRunCoordinator.class);
        HiveControlInboxService inboxService = mock(HiveControlInboxService.class);
        HiveEventPublishPort publisher = mock(HiveEventPublishPort.class);
        HiveInspectionCommandHandler inspectionCommandHandler = mock(HiveInspectionCommandHandler.class);
        HivePolicySyncCommandHandler policySyncCommandHandler = mock(HivePolicySyncCommandHandler.class);
        RuntimeException failure = new IllegalStateException("inspection failed");
        org.mockito.Mockito.doThrow(failure).when(inspectionCommandHandler)
                .handle(any(HiveControlCommandEnvelope.class));
        HiveControlCommandDispatcher dispatcher = new HiveControlCommandDispatcher(
                coordinator,
                inboxService,
                publisher,
                inspectionCommandHandler,
                policySyncCommandHandler,
                Clock.fixed(Instant.parse("2026-03-18T00:00:00Z"), ZoneOffset.UTC));
        HiveControlCommandEnvelope envelope = HiveControlCommandEnvelope.builder()
                .eventType("inspection.request")
                .requestId("req-2")
                .threadId("thread-1")
                .inspection(HiveInspectionRequestBody.builder().operation("sessions.list").build())
                .build();

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> dispatcher.dispatch(envelope));

        assertEquals("inspection failed", error.getMessage());
        verify(inboxService).markFailedIfPending("req-2", failure);
        verify(publisher, never()).publishCommandAcknowledged(envelope);
    }

    @Test
    void shouldMarkCancelledCommandProcessedWhenRunIsAlreadyStopped() {
        SessionRunCoordinator coordinator = mock(SessionRunCoordinator.class);
        HiveControlInboxService inboxService = mock(HiveControlInboxService.class);
        HiveEventPublishPort publisher = mock(HiveEventPublishPort.class);
        HiveInspectionCommandHandler inspectionCommandHandler = mock(HiveInspectionCommandHandler.class);
        HivePolicySyncCommandHandler policySyncCommandHandler = mock(HivePolicySyncCommandHandler.class);
        when(coordinator.submit(any(Message.class), any(Runnable.class))).thenReturn(
                CompletableFuture.failedFuture(new IllegalStateException("Cancelled by Hive control command")));
        HiveControlCommandDispatcher dispatcher = new HiveControlCommandDispatcher(
                coordinator,
                inboxService,
                publisher,
                inspectionCommandHandler,
                policySyncCommandHandler,
                Clock.fixed(Instant.parse("2026-03-18T00:00:00Z"), ZoneOffset.UTC));
        HiveControlCommandEnvelope envelope = HiveControlCommandEnvelope.builder()
                .commandId("cmd-3")
                .threadId("thread-1")
                .body("cancelled")
                .build();

        dispatcher.dispatch(envelope);

        verify(inboxService).markProcessed("cmd-3");
        verify(publisher).publishCommandAcknowledged(envelope);
    }
}
