package me.golemcore.bot.adapter.inbound.web.terminal;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntConsumer;

/**
 * Polls a {@link TerminalConnection.ExitWaitStrategy} in a background daemon
 * thread and invokes {@code onExit} at most once when the underlying process
 * terminates. Extracted from {@link TerminalConnection} so the polling loop can
 * be exercised in isolation without spawning a real pty.
 */
@Slf4j
final class ExitWatcher {

    private final TerminalConnection.ExitWaitStrategy strategy;
    private final IntConsumer onExit;
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private final Thread thread;

    ExitWatcher(TerminalConnection.ExitWaitStrategy strategy, IntConsumer onExit) {
        this.strategy = strategy;
        this.onExit = onExit;
        this.thread = new Thread(this::loop, "terminal-exit-watcher");
        this.thread.setDaemon(true);
    }

    void start() {
        if (started.compareAndSet(false, true)) {
            thread.start();
        }
    }

    void stop() {
        if (stopped.compareAndSet(false, true)) {
            thread.interrupt();
        }
    }

    boolean isStopped() {
        return stopped.get();
    }

    private void loop() {
        try {
            while (!stopped.get()) {
                TerminalConnection.ExitWaitOutcome outcome = strategy.awaitExit();
                if (outcome.isTerminated()) {
                    if (!stopped.get()) {
                        onExit.accept(outcome.exitCode());
                    }
                    return;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
