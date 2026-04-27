package me.golemcore.bot.domain.scheduling;

import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.domain.model.DelayedActionDeliveryMode;
import me.golemcore.bot.domain.model.DelayedActionKind;
import me.golemcore.bot.domain.model.DelayedSessionAction;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DelayedSessionActionSchedulerTest {

    private static final Instant NOW = Instant.parse("2026-03-19T18:30:00Z");

    @Test
    void shouldRescheduleRetryableDispatchWithBackoff() {
        DelayedSessionActionService delayedActionService = mock(DelayedSessionActionService.class);
        DelayedActionDispatcher delayedActionDispatcher = mock(DelayedActionDispatcher.class);
        RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);
        when(runtimeConfigService.isDelayedActionsEnabled()).thenReturn(true);
        when(runtimeConfigService.getDelayedActionsTickSeconds()).thenReturn(60);

        DelayedSessionAction action = DelayedSessionAction.builder()
                .id("delay-1")
                .kind(DelayedActionKind.RUN_LATER)
                .deliveryMode(DelayedActionDeliveryMode.INTERNAL_TURN)
                .attempts(0)
                .maxAttempts(4)
                .build();
        when(delayedActionService.leaseDueActions(10)).thenReturn(List.of(action));
        when(delayedActionDispatcher.dispatch(action)).thenReturn(CompletableFuture.completedFuture(
                DelayedActionDispatcher.DispatchResult.retryable("temporary")));

        DelayedSessionActionScheduler scheduler = new DelayedSessionActionScheduler(
                delayedActionService,
                delayedActionDispatcher,
                runtimeConfigService,
                Clock.fixed(NOW, ZoneOffset.UTC));
        scheduler.init();
        try {
            scheduler.tick();

            verify(delayedActionService, timeout(2000)).rescheduleRetry(
                    eq("delay-1"),
                    eq(NOW.plusSeconds(30)),
                    eq("temporary"));
            verify(delayedActionService, never()).markDeadLetter(eq("delay-1"), eq("temporary"));
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    void shouldMarkDeadLetterWhenRetryBudgetIsExhausted() {
        DelayedSessionActionService delayedActionService = mock(DelayedSessionActionService.class);
        DelayedActionDispatcher delayedActionDispatcher = mock(DelayedActionDispatcher.class);
        RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);
        when(runtimeConfigService.isDelayedActionsEnabled()).thenReturn(true);
        when(runtimeConfigService.getDelayedActionsTickSeconds()).thenReturn(60);

        DelayedSessionAction action = DelayedSessionAction.builder()
                .id("delay-2")
                .kind(DelayedActionKind.RUN_LATER)
                .deliveryMode(DelayedActionDeliveryMode.INTERNAL_TURN)
                .attempts(3)
                .maxAttempts(4)
                .build();
        when(delayedActionService.leaseDueActions(10)).thenReturn(List.of(action));
        when(delayedActionDispatcher.dispatch(action)).thenReturn(CompletableFuture.completedFuture(
                DelayedActionDispatcher.DispatchResult.retryable("temporary")));

        DelayedSessionActionScheduler scheduler = new DelayedSessionActionScheduler(
                delayedActionService,
                delayedActionDispatcher,
                runtimeConfigService,
                Clock.fixed(NOW, ZoneOffset.UTC));
        scheduler.init();
        try {
            scheduler.tick();

            verify(delayedActionService, timeout(2000)).markDeadLetter(eq("delay-2"), eq("temporary"));
            verify(delayedActionService, never()).rescheduleRetry(eq("delay-2"), eq(NOW.plusSeconds(30)),
                    eq("temporary"));
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    void shouldSkipTickWhenDelayedActionsAreDisabled() {
        DelayedSessionActionService delayedActionService = mock(DelayedSessionActionService.class);
        RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);
        when(runtimeConfigService.isDelayedActionsEnabled()).thenReturn(false);

        DelayedSessionActionScheduler scheduler = new DelayedSessionActionScheduler(
                delayedActionService,
                mock(DelayedActionDispatcher.class),
                runtimeConfigService,
                Clock.fixed(NOW, ZoneOffset.UTC));

        scheduler.tick();

        verify(delayedActionService, never()).leaseDueActions(10);
    }

    @Test
    @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
    void shouldSkipTickWhenAnotherTickIsAlreadyRunning() throws Exception {
        DelayedSessionActionService delayedActionService = mock(DelayedSessionActionService.class);
        RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);
        when(runtimeConfigService.isDelayedActionsEnabled()).thenReturn(true);

        DelayedSessionActionScheduler scheduler = new DelayedSessionActionScheduler(
                delayedActionService,
                mock(DelayedActionDispatcher.class),
                runtimeConfigService,
                Clock.fixed(NOW, ZoneOffset.UTC));

        java.lang.reflect.Field tickingField = DelayedSessionActionScheduler.class.getDeclaredField("ticking");
        tickingField.setAccessible(true);
        java.util.concurrent.atomic.AtomicBoolean ticking = (java.util.concurrent.atomic.AtomicBoolean) tickingField
                .get(scheduler);
        ticking.set(true);

        scheduler.tick();

        verify(delayedActionService, never()).leaseDueActions(10);
    }

    @Test
    void shouldSwallowLeaseFailuresDuringTick() {
        DelayedSessionActionService delayedActionService = mock(DelayedSessionActionService.class);
        RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);
        when(runtimeConfigService.isDelayedActionsEnabled()).thenReturn(true);
        when(delayedActionService.leaseDueActions(10)).thenThrow(new IllegalStateException("lease failed"));

        DelayedSessionActionScheduler scheduler = new DelayedSessionActionScheduler(
                delayedActionService,
                mock(DelayedActionDispatcher.class),
                runtimeConfigService,
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertDoesNotThrow(scheduler::tick);
        verify(delayedActionService).leaseDueActions(10);
    }

    @Test
    void shouldMarkCompletedOnSuccessfulDispatch() {
        DelayedSessionActionService delayedActionService = mock(DelayedSessionActionService.class);
        DelayedActionDispatcher delayedActionDispatcher = mock(DelayedActionDispatcher.class);
        RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);
        when(runtimeConfigService.isDelayedActionsEnabled()).thenReturn(true);
        when(runtimeConfigService.getDelayedActionsTickSeconds()).thenReturn(60);

        DelayedSessionAction action = DelayedSessionAction.builder()
                .id("delay-complete")
                .kind(DelayedActionKind.RUN_LATER)
                .deliveryMode(DelayedActionDeliveryMode.INTERNAL_TURN)
                .attempts(0)
                .maxAttempts(4)
                .build();
        when(delayedActionService.leaseDueActions(10)).thenReturn(List.of(action));
        when(delayedActionDispatcher.dispatch(action))
                .thenReturn(CompletableFuture.completedFuture(DelayedActionDispatcher.DispatchResult.completed()));

        DelayedSessionActionScheduler scheduler = new DelayedSessionActionScheduler(
                delayedActionService,
                delayedActionDispatcher,
                runtimeConfigService,
                Clock.fixed(NOW, ZoneOffset.UTC));
        scheduler.init();
        try {
            scheduler.tick();

            verify(delayedActionService, timeout(2000)).markCompleted("delay-complete");
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    void shouldMarkDeadLetterOnTerminalDispatchResult() {
        DelayedSessionActionService delayedActionService = mock(DelayedSessionActionService.class);
        DelayedActionDispatcher delayedActionDispatcher = mock(DelayedActionDispatcher.class);
        RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);
        when(runtimeConfigService.isDelayedActionsEnabled()).thenReturn(true);
        when(runtimeConfigService.getDelayedActionsTickSeconds()).thenReturn(60);

        DelayedSessionAction action = DelayedSessionAction.builder()
                .id("delay-terminal")
                .kind(DelayedActionKind.RUN_LATER)
                .deliveryMode(DelayedActionDeliveryMode.INTERNAL_TURN)
                .attempts(0)
                .maxAttempts(4)
                .build();
        when(delayedActionService.leaseDueActions(10)).thenReturn(List.of(action));
        when(delayedActionDispatcher.dispatch(action)).thenReturn(CompletableFuture.completedFuture(
                DelayedActionDispatcher.DispatchResult.terminal("policy denied")));

        DelayedSessionActionScheduler scheduler = new DelayedSessionActionScheduler(
                delayedActionService,
                delayedActionDispatcher,
                runtimeConfigService,
                Clock.fixed(NOW, ZoneOffset.UTC));
        scheduler.init();
        try {
            scheduler.tick();

            verify(delayedActionService, timeout(2000)).markDeadLetter("delay-terminal", "policy denied");
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    void shouldRetryWhenDispatchFutureFails() {
        DelayedSessionActionService delayedActionService = mock(DelayedSessionActionService.class);
        DelayedActionDispatcher delayedActionDispatcher = mock(DelayedActionDispatcher.class);
        RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);
        when(runtimeConfigService.isDelayedActionsEnabled()).thenReturn(true);
        when(runtimeConfigService.getDelayedActionsTickSeconds()).thenReturn(60);

        DelayedSessionAction action = DelayedSessionAction.builder()
                .id("delay-failed-future")
                .kind(DelayedActionKind.RUN_LATER)
                .deliveryMode(DelayedActionDeliveryMode.INTERNAL_TURN)
                .attempts(1)
                .maxAttempts(5)
                .build();
        when(delayedActionService.leaseDueActions(10)).thenReturn(List.of(action));
        when(delayedActionDispatcher.dispatch(action))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("queue down")));

        DelayedSessionActionScheduler scheduler = new DelayedSessionActionScheduler(
                delayedActionService,
                delayedActionDispatcher,
                runtimeConfigService,
                Clock.fixed(NOW, ZoneOffset.UTC));
        scheduler.init();
        try {
            scheduler.tick();

            verify(delayedActionService, timeout(2000)).rescheduleRetry(
                    eq("delay-failed-future"),
                    eq(NOW.plus(java.time.Duration.ofMinutes(2))),
                    eq("Dispatch failure: queue down"));
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    void shouldRetryWhenDispatchReturnsNullResult() {
        DelayedSessionActionService delayedActionService = mock(DelayedSessionActionService.class);
        DelayedActionDispatcher delayedActionDispatcher = mock(DelayedActionDispatcher.class);
        RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);
        when(runtimeConfigService.isDelayedActionsEnabled()).thenReturn(true);
        when(runtimeConfigService.getDelayedActionsTickSeconds()).thenReturn(60);

        DelayedSessionAction action = DelayedSessionAction.builder()
                .id("delay-null-result")
                .kind(DelayedActionKind.RUN_LATER)
                .deliveryMode(DelayedActionDeliveryMode.INTERNAL_TURN)
                .attempts(2)
                .maxAttempts(6)
                .build();
        when(delayedActionService.leaseDueActions(10)).thenReturn(List.of(action));
        when(delayedActionDispatcher.dispatch(action)).thenReturn(CompletableFuture.completedFuture(null));

        DelayedSessionActionScheduler scheduler = new DelayedSessionActionScheduler(
                delayedActionService,
                delayedActionDispatcher,
                runtimeConfigService,
                Clock.fixed(NOW, ZoneOffset.UTC));
        scheduler.init();
        try {
            scheduler.tick();

            verify(delayedActionService, timeout(2000)).rescheduleRetry(
                    eq("delay-null-result"),
                    eq(NOW.plus(java.time.Duration.ofMinutes(10))),
                    eq("Dispatch returned no result"));
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    void shouldUseOneHourBackoffForLaterRetries() {
        DelayedSessionActionService delayedActionService = mock(DelayedSessionActionService.class);
        DelayedActionDispatcher delayedActionDispatcher = mock(DelayedActionDispatcher.class);
        RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);
        when(runtimeConfigService.isDelayedActionsEnabled()).thenReturn(true);
        when(runtimeConfigService.getDelayedActionsTickSeconds()).thenReturn(60);

        DelayedSessionAction action = DelayedSessionAction.builder()
                .id("delay-hour-backoff")
                .kind(DelayedActionKind.RUN_LATER)
                .deliveryMode(DelayedActionDeliveryMode.INTERNAL_TURN)
                .attempts(3)
                .maxAttempts(10)
                .build();
        when(delayedActionService.leaseDueActions(10)).thenReturn(List.of(action));
        when(delayedActionDispatcher.dispatch(action)).thenReturn(CompletableFuture.completedFuture(
                DelayedActionDispatcher.DispatchResult.retryable("retry again")));

        DelayedSessionActionScheduler scheduler = new DelayedSessionActionScheduler(
                delayedActionService,
                delayedActionDispatcher,
                runtimeConfigService,
                Clock.fixed(NOW, ZoneOffset.UTC));
        scheduler.init();
        try {
            scheduler.tick();

            verify(delayedActionService, timeout(2000)).rescheduleRetry(
                    eq("delay-hour-backoff"),
                    eq(NOW.plus(java.time.Duration.ofHours(1))),
                    eq("retry again"));
        } finally {
            scheduler.shutdown();
        }
    }
}
