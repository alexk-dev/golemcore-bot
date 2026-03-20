package me.golemcore.bot.domain.service;

import me.golemcore.bot.domain.model.DelayedActionDeliveryMode;
import me.golemcore.bot.domain.model.DelayedActionKind;
import me.golemcore.bot.domain.model.DelayedActionStatus;
import me.golemcore.bot.domain.model.DelayedJobReadyEvent;
import me.golemcore.bot.domain.model.DelayedSessionAction;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.port.outbound.StoragePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DelayedSessionActionServiceTest {

    private static final Instant NOW = Instant.parse("2026-03-19T18:30:00Z");

    private StoragePort storagePort;
    private RuntimeConfigService runtimeConfigService;
    private DelayedSessionActionService service;
    private Map<String, String> storedText;

    @BeforeEach
    void setUp() {
        storagePort = mock(StoragePort.class);
        runtimeConfigService = mock(RuntimeConfigService.class);
        storedText = new ConcurrentHashMap<>();

        when(storagePort.putTextAtomic(anyString(), anyString(), anyString(), anyBoolean()))
                .thenAnswer(invocation -> {
                    String directory = invocation.getArgument(0);
                    String fileName = invocation.getArgument(1);
                    String content = invocation.getArgument(2);
                    storedText.put(directory + "/" + fileName, content);
                    return CompletableFuture.completedFuture(null);
                });
        when(storagePort.getText(anyString(), anyString()))
                .thenAnswer(invocation -> {
                    String directory = invocation.getArgument(0);
                    String fileName = invocation.getArgument(1);
                    return CompletableFuture.completedFuture(storedText.get(directory + "/" + fileName));
                });
        when(runtimeConfigService.isDelayedActionsEnabled()).thenReturn(true);
        when(runtimeConfigService.getDelayedActionsMaxPendingPerSession()).thenReturn(50);
        when(runtimeConfigService.getDelayedActionsMaxDelay()).thenReturn(Duration.ofDays(30));
        when(runtimeConfigService.getDelayedActionsDefaultMaxAttempts()).thenReturn(4);
        when(runtimeConfigService.getDelayedActionsLeaseDuration()).thenReturn(Duration.ofMinutes(2));
        when(runtimeConfigService.getDelayedActionsRetentionAfterCompletion()).thenReturn(Duration.ofDays(7));

        service = new DelayedSessionActionService(storagePort, runtimeConfigService, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void shouldScheduleAndListRunLaterAction() {
        DelayedSessionAction created = service.schedule(DelayedSessionAction.builder()
                .channelType("telegram")
                .conversationKey("conv-1")
                .transportChatId("chat-1")
                .kind(DelayedActionKind.RUN_LATER)
                .deliveryMode(DelayedActionDeliveryMode.INTERNAL_TURN)
                .runAt(NOW.plus(Duration.ofMinutes(5)))
                .createdBy("test")
                .payload(Map.of("instruction", "Start the report"))
                .build());

        assertNotNull(created.getId());
        List<DelayedSessionAction> actions = service.listActions("telegram", "conv-1");
        assertEquals(1, actions.size());
        assertEquals(DelayedActionKind.RUN_LATER, actions.get(0).getKind());
        assertTrue(storedText.containsKey("automation/delayed-actions.json"));
    }

    @Test
    void shouldReturnExistingActionForMatchingDedupeKey() {
        DelayedSessionAction first = service.schedule(DelayedSessionAction.builder()
                .channelType("telegram")
                .conversationKey("conv-1")
                .transportChatId("chat-1")
                .kind(DelayedActionKind.REMIND_LATER)
                .deliveryMode(DelayedActionDeliveryMode.DIRECT_MESSAGE)
                .runAt(NOW.plusSeconds(30))
                .dedupeKey("same-key")
                .payload(Map.of("message", "Reminder"))
                .build());

        DelayedSessionAction second = service.schedule(DelayedSessionAction.builder()
                .channelType("telegram")
                .conversationKey("conv-1")
                .transportChatId("chat-1")
                .kind(DelayedActionKind.REMIND_LATER)
                .deliveryMode(DelayedActionDeliveryMode.DIRECT_MESSAGE)
                .runAt(NOW.plusSeconds(60))
                .dedupeKey("same-key")
                .payload(Map.of("message", "Reminder again"))
                .build());

        assertEquals(first.getId(), second.getId());
        assertEquals(1, service.listActions("telegram", "conv-1").size());
    }

    @Test
    void shouldCancelFutureReminderOnUserActivity() {
        DelayedSessionAction created = service.schedule(DelayedSessionAction.builder()
                .channelType("telegram")
                .conversationKey("conv-1")
                .transportChatId("chat-1")
                .kind(DelayedActionKind.REMIND_LATER)
                .deliveryMode(DelayedActionDeliveryMode.DIRECT_MESSAGE)
                .runAt(NOW.plus(Duration.ofMinutes(10)))
                .cancelOnUserActivity(true)
                .payload(Map.of("message", "Reminder"))
                .build());

        Message inbound = Message.builder()
                .role("user")
                .content("Actually, do it now")
                .channelType("telegram")
                .chatId("conv-1")
                .metadata(new LinkedHashMap<>())
                .build();

        service.cancelOnUserActivity(inbound);

        DelayedSessionAction updated = service.get(created.getId()).orElseThrow();
        assertEquals(DelayedActionStatus.CANCELLED, updated.getStatus());
    }

    @Test
    void shouldLeaseAndCompleteDueAction() {
        DelayedSessionAction created = service.schedule(DelayedSessionAction.builder()
                .channelType("telegram")
                .conversationKey("conv-1")
                .transportChatId("chat-1")
                .kind(DelayedActionKind.REMIND_LATER)
                .deliveryMode(DelayedActionDeliveryMode.DIRECT_MESSAGE)
                .runAt(NOW)
                .payload(Map.of("message", "Reminder"))
                .build());

        List<DelayedSessionAction> leased = service.leaseDueActions(10);

        assertEquals(1, leased.size());
        assertEquals(created.getId(), leased.get(0).getId());
        assertEquals(DelayedActionStatus.LEASED, service.get(created.getId()).orElseThrow().getStatus());

        service.markCompleted(created.getId());

        assertEquals(DelayedActionStatus.COMPLETED, service.get(created.getId()).orElseThrow().getStatus());
    }

    @Test
    void shouldReLeaseActionAfterLeaseExpires() {
        DelayedSessionAction created = service.schedule(DelayedSessionAction.builder()
                .channelType("telegram")
                .conversationKey("conv-1")
                .transportChatId("chat-1")
                .kind(DelayedActionKind.RUN_LATER)
                .deliveryMode(DelayedActionDeliveryMode.INTERNAL_TURN)
                .runAt(NOW)
                .payload(Map.of("instruction", "Resume"))
                .build());

        List<DelayedSessionAction> firstLease = service.leaseDueActions(10);

        assertEquals(1, firstLease.size());
        assertEquals(DelayedActionStatus.LEASED, service.get(created.getId()).orElseThrow().getStatus());

        DelayedSessionActionService recovered = new DelayedSessionActionService(
                storagePort,
                runtimeConfigService,
                Clock.fixed(NOW.plus(Duration.ofMinutes(3)), ZoneOffset.UTC));

        List<DelayedSessionAction> secondLease = recovered.leaseDueActions(10);

        assertEquals(1, secondLease.size());
        assertEquals(created.getId(), secondLease.get(0).getId());
        assertEquals(DelayedActionStatus.LEASED, recovered.get(created.getId()).orElseThrow().getStatus());
    }

    @Test
    void shouldScheduleJobReadyEvent() {
        DelayedSessionAction created = service.scheduleJobReadyNotification(new DelayedJobReadyEvent(
                "telegram",
                "conv-1",
                "chat-1",
                "job-1",
                "The export is ready",
                "artifacts/export.txt",
                "export.txt",
                "text/plain"));

        assertEquals(DelayedActionKind.NOTIFY_JOB_READY, created.getKind());
        assertEquals(DelayedActionDeliveryMode.DIRECT_FILE, created.getDeliveryMode());
    }

    @Test
    void shouldScopeJobReadyDedupeToSession() {
        DelayedSessionAction first = service.scheduleJobReadyNotification(new DelayedJobReadyEvent(
                "telegram",
                "conv-1",
                "chat-1",
                "job-1",
                "Ready",
                null,
                null,
                null));
        DelayedSessionAction second = service.scheduleJobReadyNotification(new DelayedJobReadyEvent(
                "telegram",
                "conv-2",
                "chat-2",
                "job-1",
                "Ready",
                null,
                null,
                null));

        assertNotNull(first.getDedupeKey());
        assertNotNull(second.getDedupeKey());
        assertFalse(first.getId().equals(second.getId()));
        assertEquals(1, service.listActions("telegram", "conv-1").size());
        assertEquals(1, service.listActions("telegram", "conv-2").size());
    }

    @Test
    void shouldNotRunNowWhileActionLeaseIsActive() {
        DelayedSessionAction created = service.schedule(DelayedSessionAction.builder()
                .channelType("telegram")
                .conversationKey("conv-1")
                .transportChatId("chat-1")
                .kind(DelayedActionKind.RUN_LATER)
                .deliveryMode(DelayedActionDeliveryMode.INTERNAL_TURN)
                .runAt(NOW)
                .payload(Map.of("instruction", "Resume"))
                .build());

        service.leaseDueActions(10);

        boolean updated = service.runNow(created.getId(), "telegram", "conv-1");

        assertFalse(updated);
        assertEquals(DelayedActionStatus.LEASED, service.get(created.getId()).orElseThrow().getStatus());
    }

    @Test
    void shouldRejectWebhookDelayedActions() {
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> service.schedule(
                DelayedSessionAction.builder()
                        .channelType("webhook")
                        .conversationKey("conv-1")
                        .transportChatId("chat-1")
                        .kind(DelayedActionKind.REMIND_LATER)
                        .deliveryMode(DelayedActionDeliveryMode.DIRECT_MESSAGE)
                        .runAt(NOW.plusSeconds(60))
                        .payload(Map.of("message", "Reminder"))
                        .build()));

        assertTrue(error.getMessage().contains("not supported"));
    }

    @Test
    void shouldStartWithEmptyRegistryWhenLoadFails() {
        when(storagePort.getText(DelayedSessionActionService.AUTOMATION_DIR, DelayedSessionActionService.ACTIONS_FILE))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("boom")));

        DelayedSessionActionService failingService = new DelayedSessionActionService(
                storagePort,
                runtimeConfigService,
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertTrue(failingService.listActions("telegram", "conv-1").isEmpty());
    }
}
