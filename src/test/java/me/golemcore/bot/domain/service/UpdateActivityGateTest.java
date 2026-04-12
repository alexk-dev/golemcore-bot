package me.golemcore.bot.domain.service;

import me.golemcore.bot.domain.model.UpdateBlockedReason;
import me.golemcore.bot.port.outbound.AutoExecutionStatusPort;
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
        AutoExecutionStatusPort autoExecutionStatusPort = mock(AutoExecutionStatusPort.class);
        when(coordinator.hasActiveOrQueuedWork()).thenReturn(false);
        when(autoExecutionStatusPort.isAutoJobExecuting()).thenReturn(false);

        UpdateActivityGate.Result result = new UpdateActivityGate(coordinator, autoExecutionStatusPort).getStatus();

        assertFalse(result.busy());
        assertNull(result.blockedReason());
    }

    @Test
    void shouldReportSessionWorkBeforeApply() {
        SessionRunCoordinator coordinator = mock(SessionRunCoordinator.class);
        AutoExecutionStatusPort autoExecutionStatusPort = mock(AutoExecutionStatusPort.class);
        when(coordinator.hasActiveOrQueuedWork()).thenReturn(true);
        when(autoExecutionStatusPort.isAutoJobExecuting()).thenReturn(false);

        UpdateActivityGate.Result result = new UpdateActivityGate(coordinator, autoExecutionStatusPort).getStatus();

        assertTrue(result.busy());
        assertEquals(UpdateBlockedReason.SESSION_WORK_RUNNING, result.blockedReason());
    }

    @Test
    void shouldReportAutoJobExecutionAsBlocker() {
        SessionRunCoordinator coordinator = mock(SessionRunCoordinator.class);
        AutoExecutionStatusPort autoExecutionStatusPort = mock(AutoExecutionStatusPort.class);
        when(coordinator.hasActiveOrQueuedWork()).thenReturn(false);
        when(autoExecutionStatusPort.isAutoJobExecuting()).thenReturn(true);

        UpdateActivityGate.Result result = new UpdateActivityGate(coordinator, autoExecutionStatusPort).getStatus();

        assertTrue(result.busy());
        assertEquals(UpdateBlockedReason.AUTO_JOB_RUNNING, result.blockedReason());
    }
}
