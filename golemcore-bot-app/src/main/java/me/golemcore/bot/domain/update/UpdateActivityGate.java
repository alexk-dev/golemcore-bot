package me.golemcore.bot.domain.update;

import me.golemcore.bot.domain.session.SessionRunCoordinator;
import me.golemcore.bot.domain.model.UpdateBlockedReason;
import me.golemcore.bot.port.outbound.AutoExecutionStatusPort;
import org.springframework.stereotype.Service;

@Service
public class UpdateActivityGate {

    private final SessionRunCoordinator sessionRunCoordinator;
    private final AutoExecutionStatusPort autoExecutionStatusPort;

    public UpdateActivityGate(
            SessionRunCoordinator sessionRunCoordinator,
            AutoExecutionStatusPort autoExecutionStatusPort) {
        this.sessionRunCoordinator = sessionRunCoordinator;
        this.autoExecutionStatusPort = autoExecutionStatusPort;
    }

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
