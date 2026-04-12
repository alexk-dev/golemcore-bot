package me.golemcore.bot.domain.service;

import lombok.RequiredArgsConstructor;
import me.golemcore.bot.domain.model.UpdateBlockedReason;
import me.golemcore.bot.port.outbound.AutoExecutionStatusPort;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UpdateActivityGate {

    private final SessionRunCoordinator sessionRunCoordinator;
    private final AutoExecutionStatusPort autoExecutionStatusPort;

    public Result getStatus() {
        if (autoExecutionStatusPort.isAutoJobExecuting()) {
            return Result.busy(UpdateBlockedReason.AUTO_JOB_RUNNING);
        }
        if (sessionRunCoordinator.hasActiveOrQueuedWork()) {
            return Result.busy(UpdateBlockedReason.SESSION_WORK_RUNNING);
        }
        return Result.idle();
    }

    public record Result(boolean busy, UpdateBlockedReason blockedReason) {
        public static Result idle() {
            return new Result(false, null);
        }

        public static Result busy(UpdateBlockedReason blockedReason) {
            return new Result(true, blockedReason);
        }
    }
}
