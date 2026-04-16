package me.golemcore.bot.adapter.inbound.web.terminal;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.infrastructure.config.BotProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tracks concurrent terminal sessions per dashboard user and enforces the
 * configured feature flag, per-user concurrency cap, idle timeout, and absolute
 * session duration. Leases released through {@link Lease#release()} free the
 * slot for the same user to reconnect. User entries are evicted once their last
 * lease is released so long-lived instances do not accumulate stale keys.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TerminalSessionLimiter {

    private final BotProperties botProperties;
    private final Map<String, AtomicInteger> activeByUser = new ConcurrentHashMap<>();

    public boolean isEnabled() {
        return botProperties.getDashboard().getTerminal().isEnabled();
    }

    public Duration idleTimeout() {
        return botProperties.getDashboard().getTerminal().getIdleTimeout();
    }

    public Duration maxSessionDuration() {
        return botProperties.getDashboard().getTerminal().getMaxSessionDuration();
    }

    public Optional<Lease> tryAcquire(String username) {
        if (!isEnabled()) {
            return Optional.empty();
        }
        int maxPerUser = botProperties.getDashboard().getTerminal().getMaxSessionsPerUser();
        if (maxPerUser <= 0) {
            return Optional.empty();
        }
        AtomicReference<Lease> granted = new AtomicReference<>();
        activeByUser.compute(username, (key, existing) -> {
            AtomicInteger counter = existing != null ? existing : new AtomicInteger(0);
            if (counter.get() >= maxPerUser) {
                return counter;
            }
            counter.incrementAndGet();
            granted.set(new Lease(username, this));
            return counter;
        });
        Lease lease = granted.get();
        if (lease == null) {
            log.warn("[Terminal] Rejected new session for user={}: cap {} reached", username, maxPerUser);
            return Optional.empty();
        }
        return Optional.of(lease);
    }

    public int activeCount(String username) {
        AtomicInteger counter = activeByUser.get(username);
        return counter == null ? 0 : counter.get();
    }

    /**
     * Number of distinct users currently holding at least one lease. Exposed for
     * tests so that entry-eviction can be asserted without reflection.
     */
    int trackedUserCount() {
        return activeByUser.size();
    }

    private void releaseSlot(String username) {
        activeByUser.computeIfPresent(username, (key, counter) -> {
            if (counter.decrementAndGet() <= 0) {
                return null;
            }
            return counter;
        });
    }

    public static final class Lease {
        private final String leaseUsername;
        private final TerminalSessionLimiter limiter;
        private final AtomicBoolean released = new AtomicBoolean(false);

        private Lease(String username, TerminalSessionLimiter limiter) {
            this.leaseUsername = username;
            this.limiter = limiter;
        }

        public String username() {
            return leaseUsername;
        }

        public void release() {
            if (released.compareAndSet(false, true)) {
                limiter.releaseSlot(leaseUsername);
            }
        }
    }
}
