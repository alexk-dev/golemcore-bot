package me.golemcore.bot.adapter.inbound.web.terminal;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExitWatcherTest {

    @Test
    void shouldLoopUntilStrategySignalsTermination() throws Exception {
        // Guards against the historical 24h waitFor regression: the loop must keep
        // polling the strategy until it reports a real termination outcome.
        AtomicInteger waitInvocations = new AtomicInteger(0);
        AtomicInteger observedExitCode = new AtomicInteger(-1);
        CountDownLatch exitLatch = new CountDownLatch(1);

        TerminalConnection.ExitWaitStrategy strategy = () -> {
            int call = waitInvocations.incrementAndGet();
            if (call < 3) {
                return TerminalConnection.ExitWaitOutcome.TIMEOUT;
            }
            return TerminalConnection.ExitWaitOutcome.exited(42);
        };

        ExitWatcher watcher = new ExitWatcher(strategy, code -> {
            observedExitCode.set(code);
            exitLatch.countDown();
        });
        watcher.start();
        try {
            assertTrue(exitLatch.await(5, TimeUnit.SECONDS), "exit callback should fire after loop terminates");
            assertEquals(42, observedExitCode.get());
            assertTrue(waitInvocations.get() >= 3, "strategy must be polled more than once");
        } finally {
            watcher.stop();
        }
    }

    @Test
    void shouldSuppressExitCallbackWhenStoppedBeforeTermination() throws Exception {
        CountDownLatch strategyEntered = new CountDownLatch(1);
        CountDownLatch releaseStrategy = new CountDownLatch(1);
        AtomicInteger callbackInvocations = new AtomicInteger(0);

        TerminalConnection.ExitWaitStrategy strategy = () -> {
            strategyEntered.countDown();
            releaseStrategy.await();
            return TerminalConnection.ExitWaitOutcome.exited(0);
        };

        ExitWatcher watcher = new ExitWatcher(strategy, code -> callbackInvocations.incrementAndGet());
        watcher.start();
        assertTrue(strategyEntered.await(2, TimeUnit.SECONDS), "strategy should be invoked once");
        watcher.stop();
        releaseStrategy.countDown();

        // Verify the callback stays silent for the full window — if stop() races the
        // loop, a faulty implementation would fire sometime within this budget.
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(300);
        while (System.nanoTime() < deadline) {
            assertEquals(0, callbackInvocations.get(), "callback must not fire after stop()");
            Thread.sleep(20);
        }
    }

    @Test
    void shouldIgnoreRedundantStartCalls() throws Exception {
        AtomicInteger strategyCalls = new AtomicInteger(0);
        CountDownLatch exitLatch = new CountDownLatch(1);
        TerminalConnection.ExitWaitStrategy strategy = () -> {
            if (strategyCalls.incrementAndGet() >= 2) {
                return TerminalConnection.ExitWaitOutcome.exited(0);
            }
            return TerminalConnection.ExitWaitOutcome.TIMEOUT;
        };

        ExitWatcher watcher = new ExitWatcher(strategy, code -> exitLatch.countDown());
        watcher.start();
        watcher.start();
        try {
            assertTrue(exitLatch.await(2, TimeUnit.SECONDS),
                    "a second start() must be a no-op, not a thread-state exception");
        } finally {
            watcher.stop();
        }
    }
}
