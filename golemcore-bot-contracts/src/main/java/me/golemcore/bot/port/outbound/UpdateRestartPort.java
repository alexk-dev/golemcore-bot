package me.golemcore.bot.port.outbound;

/**
 * Triggers JVM restart for staged binary updates.
 */
public interface UpdateRestartPort {

    void restart(int exitCode);
}
