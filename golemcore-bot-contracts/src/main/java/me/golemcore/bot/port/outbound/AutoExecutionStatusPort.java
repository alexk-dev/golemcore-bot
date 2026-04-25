package me.golemcore.bot.port.outbound;

/**
 * Outbound contract for checking whether autonomous runtime work is currently
 * executing.
 */
public interface AutoExecutionStatusPort {

    boolean isAutoJobExecuting();
}
