package me.golemcore.bot.infrastructure.config;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionRunConfigurationTest {

    @Test
    void shouldCreateDaemonThreadsWithCorrectName() throws Exception {
        SessionRunConfiguration config = new SessionRunConfiguration();
        ExecutorService executor = config.sessionRunExecutor();
        assertNotNull(executor);

        AtomicReference<String> threadName = new AtomicReference<>();
        AtomicReference<Boolean> isDaemon = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        executor.submit(() -> {
            threadName.set(Thread.currentThread().getName());
            isDaemon.set(Thread.currentThread().isDaemon());
            latch.countDown();
        });

        assertTrue(latch.await(2, TimeUnit.SECONDS));
        assertEquals("session-run", threadName.get());
        assertTrue(isDaemon.get());

        config.shutdown();
    }

    @Test
    void shouldShutdownCleanly() {
        SessionRunConfiguration config = new SessionRunConfiguration();
        ExecutorService executor = config.sessionRunExecutor();
        assertNotNull(executor);

        config.shutdown();

        assertTrue(executor.isShutdown());
    }
}
