package me.golemcore.bot.domain.system.toolloop.resilience;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderCircuitBreakerTest {

    private MutableClock clock;
    private ProviderCircuitBreaker breaker;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-04-16T02:40:00Z"));
        breaker = new ProviderCircuitBreaker(clock, 2, 10, 30);
    }

    @Test
    void shouldRemainClosedBeforeFailureThresholdIsReached() {
        breaker.recordFailure("openai");

        assertEquals(ProviderCircuitBreaker.State.CLOSED, breaker.getState("openai"));
        assertFalse(breaker.isOpen("openai"));
    }

    @Test
    void shouldTripOpenWhenFailuresReachThresholdInsideWindow() {
        breaker.recordFailure("openai");
        breaker.recordFailure("openai");

        assertEquals(ProviderCircuitBreaker.State.OPEN, breaker.getState("openai"));
        assertTrue(breaker.isOpen("openai"));
    }

    @Test
    void shouldResetFailureWindowWhenWindowExpiresBeforeThreshold() {
        breaker.recordFailure("openai");
        clock.plusSeconds(11);
        breaker.recordFailure("openai");

        assertEquals(ProviderCircuitBreaker.State.CLOSED, breaker.getState("openai"));
        assertFalse(breaker.isOpen("openai"));
    }

    @Test
    void shouldTransitionFromOpenToHalfOpenAfterOpenDuration() {
        tripOpen();
        clock.plusSeconds(31);

        assertFalse(breaker.isOpen("openai"));
        assertEquals(ProviderCircuitBreaker.State.HALF_OPEN, breaker.getState("openai"));
    }

    @Test
    void shouldAllowOnlyOneHalfOpenProbeUntilProbeCompletes() {
        tripOpen();
        clock.plusSeconds(31);

        assertFalse(breaker.isOpen("openai"));
        assertEquals(ProviderCircuitBreaker.State.HALF_OPEN, breaker.getState("openai"));
        assertTrue(breaker.isOpen("openai"));

        breaker.recordSuccess("openai");

        assertEquals(ProviderCircuitBreaker.State.CLOSED, breaker.getState("openai"));
        assertFalse(breaker.isOpen("openai"));
    }

    @Test
    void shouldReopenWhenHalfOpenProbeFails() {
        tripOpen();
        clock.plusSeconds(31);
        assertFalse(breaker.isOpen("openai"));

        breaker.recordFailure("openai");

        assertEquals(ProviderCircuitBreaker.State.OPEN, breaker.getState("openai"));
        assertTrue(breaker.isOpen("openai"));
    }

    @Test
    void shouldCloseWhenHalfOpenProbeSucceeds() {
        tripOpen();
        clock.plusSeconds(31);
        assertFalse(breaker.isOpen("openai"));

        breaker.recordSuccess("openai");

        assertEquals(ProviderCircuitBreaker.State.CLOSED, breaker.getState("openai"));
        assertFalse(breaker.isOpen("openai"));
    }

    @Test
    void shouldTreatUnknownProviderAsClosedAndIgnoreSuccess() {
        breaker.recordSuccess("unknown");

        assertEquals(ProviderCircuitBreaker.State.CLOSED, breaker.getState("unknown"));
        assertFalse(breaker.isOpen("unknown"));
    }

    @Test
    void shouldKeepOpenWhenOpenDurationHasNotExpiredOrOpenedAtMissing() {
        tripOpen();
        clock.plusSeconds(30);
        assertTrue(breaker.isOpen("openai"));

    }

    @Test
    void shouldSnapshotStatesWithoutAdvancingOpenBreakerToHalfOpen() {
        tripOpen();
        clock.plusSeconds(31);

        Map<String, ProviderCircuitBreaker.State> snapshot = breaker.snapshotStates();

        assertEquals(ProviderCircuitBreaker.State.OPEN, snapshot.get("openai"));
        assertEquals(ProviderCircuitBreaker.State.OPEN, breaker.getState("openai"));
    }

    @Test
    void shouldCountAdditionalFailuresWithoutRetrippingAlreadyOpenState() {
        ProviderCircuitBreaker thresholdOneBreaker = new ProviderCircuitBreaker(clock, 1, 10, 30);
        thresholdOneBreaker.recordFailure("manual");
        assertEquals(ProviderCircuitBreaker.State.OPEN, thresholdOneBreaker.getState("manual"));

        thresholdOneBreaker.recordFailure("manual");

        assertEquals(ProviderCircuitBreaker.State.OPEN, thresholdOneBreaker.getState("manual"));
    }

    @Test
    void isAvailableReturnsTrueForUnknownProvider() {
        assertTrue(breaker.isAvailable("never-seen"));
    }

    @Test
    void isAvailableReturnsFalseWhenOpenCooldownNotElapsed() {
        tripOpen();
        clock.plusSeconds(10);

        assertFalse(breaker.isAvailable("openai"));
        assertEquals(ProviderCircuitBreaker.State.OPEN, breaker.getState("openai"));
    }

    @Test
    void isAvailableDoesNotTransitionOpenToHalfOpen() {
        tripOpen();
        clock.plusSeconds(31);

        assertTrue(breaker.isAvailable("openai"));
        assertTrue(breaker.isAvailable("openai"));
        assertEquals(ProviderCircuitBreaker.State.OPEN, breaker.getState("openai"));
    }

    @Test
    void isAvailableReturnsFalseWhileHalfOpenProbeInFlight() {
        tripOpen();
        clock.plusSeconds(31);
        assertFalse(breaker.isOpen("openai"));
        assertEquals(ProviderCircuitBreaker.State.HALF_OPEN, breaker.getState("openai"));

        assertFalse(breaker.isAvailable("openai"));
        assertFalse(breaker.isAvailable("openai"));
        assertEquals(ProviderCircuitBreaker.State.HALF_OPEN, breaker.getState("openai"));
    }

    private void tripOpen() {
        breaker.recordFailure("openai");
        breaker.recordFailure("openai");
        assertTrue(breaker.isOpen("openai"));
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        void plusSeconds(long seconds) {
            now = now.plusSeconds(seconds);
        }
    }
}
