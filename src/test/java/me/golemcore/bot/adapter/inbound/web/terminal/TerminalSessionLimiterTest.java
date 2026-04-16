package me.golemcore.bot.adapter.inbound.web.terminal;

import me.golemcore.bot.infrastructure.config.BotProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerminalSessionLimiterTest {

    private BotProperties botProperties;

    @BeforeEach
    void setUp() {
        botProperties = new BotProperties();
        botProperties.getDashboard().getTerminal().setEnabled(true);
        botProperties.getDashboard().getTerminal().setMaxSessionsPerUser(2);
        botProperties.getDashboard().getTerminal().setIdleTimeout(Duration.ofMinutes(15));
        botProperties.getDashboard().getTerminal().setMaxSessionDuration(Duration.ofHours(4));
    }

    @Test
    void shouldDisableTerminalByDefaultUntilOperatorOptsIn() {
        BotProperties defaultProperties = new BotProperties();
        TerminalSessionLimiter limiter = new TerminalSessionLimiter(defaultProperties);

        assertFalse(defaultProperties.getDashboard().getTerminal().isEnabled());
        assertFalse(limiter.isEnabled(), "terminal access should require an explicit operator opt-in");
    }

    @Test
    void shouldReportDisabledWhenFeatureFlagOff() {
        botProperties.getDashboard().getTerminal().setEnabled(false);
        TerminalSessionLimiter limiter = new TerminalSessionLimiter(botProperties);
        assertFalse(limiter.isEnabled(), "limiter should report disabled when terminal feature flag is off");
    }

    @Test
    void shouldGrantLeasesUpToConcurrencyCap() {
        TerminalSessionLimiter limiter = new TerminalSessionLimiter(botProperties);
        Optional<TerminalSessionLimiter.Lease> first = limiter.tryAcquire("admin");
        Optional<TerminalSessionLimiter.Lease> second = limiter.tryAcquire("admin");
        Optional<TerminalSessionLimiter.Lease> third = limiter.tryAcquire("admin");

        assertTrue(first.isPresent(), "first lease should be granted");
        assertTrue(second.isPresent(), "second lease should be granted");
        assertFalse(third.isPresent(), "third lease should be rejected when cap is two");
    }

    @Test
    void shouldAllowReAcquireAfterRelease() {
        TerminalSessionLimiter limiter = new TerminalSessionLimiter(botProperties);
        TerminalSessionLimiter.Lease first = limiter.tryAcquire("admin").orElseThrow();
        TerminalSessionLimiter.Lease second = limiter.tryAcquire("admin").orElseThrow();
        assertFalse(limiter.tryAcquire("admin").isPresent(), "cap reached");

        first.release();
        Optional<TerminalSessionLimiter.Lease> reacquired = limiter.tryAcquire("admin");
        assertTrue(reacquired.isPresent(), "slot should be available after release");

        second.release();
        reacquired.orElseThrow().release();
    }

    @Test
    void shouldCountUsersIndependently() {
        TerminalSessionLimiter limiter = new TerminalSessionLimiter(botProperties);
        limiter.tryAcquire("admin").orElseThrow();
        limiter.tryAcquire("admin").orElseThrow();

        Optional<TerminalSessionLimiter.Lease> otherUser = limiter.tryAcquire("operator");
        assertTrue(otherUser.isPresent(), "different user should have its own slot budget");
    }

    @Test
    void shouldRejectAcquireWhenDisabled() {
        botProperties.getDashboard().getTerminal().setEnabled(false);
        TerminalSessionLimiter limiter = new TerminalSessionLimiter(botProperties);
        assertFalse(limiter.tryAcquire("admin").isPresent(), "disabled limiter should never grant leases");
    }

    @Test
    void shouldRejectAcquireWhenMaxSessionsPerUserIsNonPositive() {
        botProperties.getDashboard().getTerminal().setMaxSessionsPerUser(0);
        TerminalSessionLimiter limiter = new TerminalSessionLimiter(botProperties);

        assertFalse(limiter.tryAcquire("admin").isPresent(), "non-positive caps should disable new terminal leases");
        assertEquals(0, limiter.activeCount("admin"));
    }

    @Test
    void shouldExposeIdleAndMaxDurations() {
        TerminalSessionLimiter limiter = new TerminalSessionLimiter(botProperties);
        assertEquals(Duration.ofMinutes(15), limiter.idleTimeout());
        assertEquals(Duration.ofHours(4), limiter.maxSessionDuration());
    }

    @Test
    void shouldEvictUserEntryWhenAllLeasesReleased() {
        TerminalSessionLimiter limiter = new TerminalSessionLimiter(botProperties);
        assertEquals(0, limiter.trackedUserCount(), "no users tracked before any acquire");

        TerminalSessionLimiter.Lease first = limiter.tryAcquire("admin").orElseThrow();
        TerminalSessionLimiter.Lease second = limiter.tryAcquire("admin").orElseThrow();
        assertEquals(1, limiter.trackedUserCount(), "one user tracked while leases are held");

        first.release();
        assertEquals(1, limiter.trackedUserCount(), "user still tracked while one lease remains");
        second.release();
        assertEquals(0, limiter.trackedUserCount(), "user entry should be evicted when all leases released");
    }

    @Test
    @SuppressWarnings("PMD.CloseResource")
    void shouldTreatDoubleReleaseAsIdempotentUnderConcurrency() throws Exception {
        TerminalSessionLimiter limiter = new TerminalSessionLimiter(botProperties);
        TerminalSessionLimiter.Lease leaseA = limiter.tryAcquire("admin").orElseThrow();
        TerminalSessionLimiter.Lease leaseB = limiter.tryAcquire("admin").orElseThrow();
        assertEquals(2, limiter.activeCount("admin"));

        int threads = 32;
        ExecutorService exec = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(exec.submit(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    leaseA.release();
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(5, TimeUnit.SECONDS);
            }
        } finally {
            exec.shutdownNow();
        }

        assertEquals(1, limiter.activeCount("admin"),
                "leaseB must still be held — concurrent double release of leaseA must decrement at most once");
        leaseB.release();
        assertEquals(0, limiter.activeCount("admin"));
        assertEquals(0, limiter.trackedUserCount());
    }
}
