package me.golemcore.bot.domain.service;

import me.golemcore.bot.domain.model.DelayedActionDeliveryMode;
import me.golemcore.bot.domain.model.DelayedActionKind;
import me.golemcore.bot.domain.model.DelayedSessionAction;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;

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
}
