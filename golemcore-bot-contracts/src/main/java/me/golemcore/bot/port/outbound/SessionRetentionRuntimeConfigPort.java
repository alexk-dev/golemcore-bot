package me.golemcore.bot.port.outbound;

import java.time.Duration;

/**
 * Runtime-config view required by session retention ownership.
 */
public interface SessionRetentionRuntimeConfigPort {

    boolean isSessionRetentionEnabled();

    Duration getSessionRetentionMaxAge();

    Duration getSessionRetentionCleanupInterval();

    boolean isSessionRetentionProtectActiveSessions();

    boolean isSessionRetentionProtectSessionsWithPlans();

    boolean isSessionRetentionProtectSessionsWithDelayedActions();
}
