package me.golemcore.bot.adapter.inbound.web.terminal;

import me.golemcore.bot.infrastructure.config.BotProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

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
    void shouldExposeIdleAndMaxDurations() {
        TerminalSessionLimiter limiter = new TerminalSessionLimiter(botProperties);
        assertEquals(Duration.ofMinutes(15), limiter.idleTimeout());
        assertEquals(Duration.ofHours(4), limiter.maxSessionDuration());
    }
}
