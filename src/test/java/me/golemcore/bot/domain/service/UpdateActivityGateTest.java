package me.golemcore.bot.domain.service;

import me.golemcore.bot.auto.AutoModeScheduler;
import me.golemcore.bot.domain.model.UpdateBlockedReason;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UpdateActivityGateTest {

    @Test
    void shouldReportIdleWhenNoRuntimeWorkExists() {
        SessionRunCoordinator coordinator = mock(SessionRunCoordinator.class);
        AutoModeScheduler autoModeScheduler = mock(AutoModeScheduler.class);
        when(coordinator.hasActiveOrQueuedWork()).thenReturn(false);
        when(autoModeScheduler.isExecuting()).thenReturn(false);

        UpdateActivityGate.Result result = new UpdateActivityGate(coordinator, autoModeScheduler).getStatus();

        assertFalse(result.busy());
        assertNull(result.blockedReason());
    }

    @Test
    void shouldReportSessionWorkBeforeApply() {
        SessionRunCoordinator coordinator = mock(SessionRunCoordinator.class);
        AutoModeScheduler autoModeScheduler = mock(AutoModeScheduler.class);
        when(coordinator.hasActiveOrQueuedWork()).thenReturn(true);
        when(autoModeScheduler.isExecuting()).thenReturn(false);

        UpdateActivityGate.Result result = new UpdateActivityGate(coordinator, autoModeScheduler).getStatus();

        assertTrue(result.busy());
        assertEquals(UpdateBlockedReason.SESSION_WORK_RUNNING, result.blockedReason());
    }

    @Test
    void shouldReportAutoJobExecutionAsBlocker() {
        SessionRunCoordinator coordinator = mock(SessionRunCoordinator.class);
        AutoModeScheduler autoModeScheduler = mock(AutoModeScheduler.class);
        when(coordinator.hasActiveOrQueuedWork()).thenReturn(false);
        when(autoModeScheduler.isExecuting()).thenReturn(true);

        UpdateActivityGate.Result result = new UpdateActivityGate(coordinator, autoModeScheduler).getStatus();

        assertTrue(result.busy());
        assertEquals(UpdateBlockedReason.AUTO_JOB_RUNNING, result.blockedReason());
    }
}
