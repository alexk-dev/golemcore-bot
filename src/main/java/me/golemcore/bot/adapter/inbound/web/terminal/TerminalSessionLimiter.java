package me.golemcore.bot.adapter.inbound.web.terminal;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.infrastructure.config.BotProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tracks concurrent terminal sessions per dashboard user and enforces the
 * configured feature flag, per-user concurrency cap, idle timeout, and absolute
 * session duration. Leases released through {@link Lease#release()} free the
 * slot for the same user to reconnect.
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
        AtomicInteger counter = activeByUser.computeIfAbsent(username, k -> new AtomicInteger(0));
        while (true) {
            int current = counter.get();
            if (current >= maxPerUser) {
                log.warn("[Terminal] Rejected new session for user={}: cap {} reached", username, maxPerUser);
                return Optional.empty();
            }
            if (counter.compareAndSet(current, current + 1)) {
                return Optional.of(new Lease(username, counter));
            }
        }
    }

    public int activeCount(String username) {
        AtomicInteger counter = activeByUser.get(username);
        return counter == null ? 0 : counter.get();
    }

    public static final class Lease {
        private final String username;
        private final AtomicInteger counter;
        private boolean released;

        private Lease(String username, AtomicInteger counter) {
            this.username = username;
            this.counter = counter;
        }

        public String username() {
            return username;
        }

        public void release() {
            if (released) {
                return;
            }
            released = true;
            counter.updateAndGet(value -> value > 0 ? value - 1 : 0);
        }
    }
}
