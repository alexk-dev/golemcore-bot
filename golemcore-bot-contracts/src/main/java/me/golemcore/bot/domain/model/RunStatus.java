package me.golemcore.bot.domain.model;

/**
 * Public status projection for a completed or skipped turn run.
 */
public enum RunStatus {
    COMPLETED, FAILED, STOPPED, CANCELLED, QUEUED, SKIPPED
}
