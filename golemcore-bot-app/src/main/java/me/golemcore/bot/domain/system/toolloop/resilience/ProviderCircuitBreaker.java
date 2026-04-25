package me.golemcore.bot.domain.system.toolloop.resilience;

/*
 * Copyright 2026 Aleksei Kuleshov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Contact: alex@kuleshov.tech
 */

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import me.golemcore.bot.domain.model.RuntimeConfig;

/**
 * L3 — Per-provider circuit breaker (CLOSED → OPEN → HALF_OPEN).
 *
 * <p>
 * Prevents hammering a provider that is known to be down. When a provider
 * accumulates {@code failureThreshold} failures within {@code windowSeconds},
 * the breaker trips to OPEN and stays there for {@code openDurationSeconds}.
 * After the open period expires, it transitions to HALF_OPEN and allows a
 * single probe request. If the probe succeeds, the breaker resets to CLOSED; if
 * it fails, it returns to OPEN for another full cycle.
 *
 * <p>
 * This is a lightweight implementation (~80 lines of logic) with no external
 * dependencies. State is tracked per provider ID in a concurrent map.
 */
public class ProviderCircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(ProviderCircuitBreaker.class);

    public enum State {
        CLOSED, OPEN, HALF_OPEN
    }

    static class ProviderState {
        volatile State state = State.CLOSED;
        int failureCount = 0;
        Instant windowStart;
        Instant openedAt;
        boolean halfOpenProbeInFlight = false;

        ProviderState(Instant now) {
            this.windowStart = now;
        }
    }

    private final Map<String, ProviderState> providers = new ConcurrentHashMap<>();
    private final Clock clock;
    private final Supplier<Settings> settingsSupplier;

    public ProviderCircuitBreaker(Clock clock, int failureThreshold, long windowSeconds, long openDurationSeconds) {
        this.clock = clock;
        Settings fixedSettings = new Settings(failureThreshold, windowSeconds, openDurationSeconds);
        this.settingsSupplier = () -> fixedSettings;
    }

    public ProviderCircuitBreaker(Clock clock, Supplier<RuntimeConfig.ResilienceConfig> configSupplier) {
        this.clock = clock;
        this.settingsSupplier = () -> settingsFrom(configSupplier != null ? configSupplier.get() : null);
    }

    /**
     * Returns true if the provider circuit is OPEN (caller should skip it). Also
     * handles the OPEN → HALF_OPEN transition when the open period expires.
     */
    public boolean isOpen(String providerId) {
        if (providerId == null) {
            return false;
        }
        ProviderState ps = providers.get(providerId);
        if (ps == null) {
            return false;
        }

        Instant now = clock.instant();
        Settings settings = settings();
        synchronized (ps) {
            if (ps.state == State.OPEN) {
                if (ps.openedAt != null && now.isAfter(ps.openedAt.plusSeconds(settings.openDurationSeconds()))) {
                    ps.state = State.HALF_OPEN;
                    ps.halfOpenProbeInFlight = true;
                    log.info("[CircuitBreaker] Provider {} transitioned OPEN → HALF_OPEN", providerId);
                    return false;
                }
                return true;
            }
            if (ps.state == State.HALF_OPEN) {
                if (!ps.halfOpenProbeInFlight) {
                    ps.halfOpenProbeInFlight = true;
                    return false;
                }
                return true;
            }
        }
        return false;
    }

    /**
     * Read-only availability check: returns true when a probe would be admitted
     * right now without mutating breaker state.
     *
     * <p>
     * Unlike {@link #isOpen(String)}, this never performs the OPEN → HALF_OPEN
     * transition nor marks a HALF_OPEN probe as in-flight. Use from selection
     * filters that scan multiple candidates — otherwise {@code isOpen} would flip
     * every eligible candidate into HALF_OPEN even though only one will be tried.
     */
    public boolean isAvailable(String providerId) {
        if (providerId == null) {
            return true;
        }
        ProviderState ps = providers.get(providerId);
        if (ps == null) {
            return true;
        }
        Instant now = clock.instant();
        Settings settings = settings();
        synchronized (ps) {
            return switch (ps.state) {
            case CLOSED -> true;
            case OPEN -> ps.openedAt != null && now.isAfter(ps.openedAt.plusSeconds(settings.openDurationSeconds()));
            case HALF_OPEN -> !ps.halfOpenProbeInFlight;
            };
        }
    }

    /**
     * Records a failure for the provider. May trip the breaker to OPEN.
     */
    public void recordFailure(String providerId) {
        Instant now = clock.instant();
        Settings settings = settings();
        ProviderState ps = providers.computeIfAbsent(providerId, k -> new ProviderState(now));

        synchronized (ps) {
            recordFailureLocked(providerId, ps, now, settings);
        }
    }

    private void recordFailureLocked(String providerId, ProviderState ps, Instant now, Settings settings) {
        if (ps.state == State.HALF_OPEN) {
            ps.state = State.OPEN;
            ps.openedAt = now;
            ps.failureCount = 0;
            ps.halfOpenProbeInFlight = false;
            log.warn("[CircuitBreaker] Provider {} probe failed, HALF_OPEN → OPEN for {}s",
                    providerId, settings.openDurationSeconds());
            return;
        }

        if (now.isAfter(ps.windowStart.plusSeconds(settings.windowSeconds()))) {
            ps.failureCount = 1;
            ps.windowStart = now;
        } else {
            ps.failureCount++;
        }

        if (ps.failureCount >= settings.failureThreshold() && ps.state == State.CLOSED) {
            ps.state = State.OPEN;
            ps.openedAt = now;
            ps.halfOpenProbeInFlight = false;
            log.warn("[CircuitBreaker] Provider {} tripped CLOSED → OPEN ({} failures in {}s window)",
                    providerId, ps.failureCount, settings.windowSeconds());
        }
    }

    /**
     * Records a success — resets the breaker to CLOSED.
     */
    public void recordSuccess(String providerId) {
        ProviderState ps = providers.get(providerId);
        if (ps == null) {
            return;
        }
        // Resetting state/failureCount/windowStart is a compound operation. Without
        // the lock, a concurrent recordFailure could observe a half-reset snapshot
        // (e.g. state=CLOSED but stale failureCount) and trip the breaker back open.
        synchronized (ps) {
            if (ps.state == State.HALF_OPEN) {
                log.info("[CircuitBreaker] Provider {} probe succeeded, HALF_OPEN → CLOSED", providerId);
            }
            ps.state = State.CLOSED;
            ps.failureCount = 0;
            ps.windowStart = clock.instant();
            ps.halfOpenProbeInFlight = false;
        }
    }

    /**
     * Returns the current state of a provider (CLOSED if unknown).
     */
    public State getState(String providerId) {
        if (providerId == null) {
            return State.CLOSED;
        }
        ProviderState ps = providers.get(providerId);
        if (ps == null) {
            return State.CLOSED;
        }
        synchronized (ps) {
            return ps.state;
        }
    }

    /**
     * Returns current states for all providers observed by this breaker.
     */
    public Map<String, State> snapshotStates() {
        Map<String, State> snapshot = new LinkedHashMap<>();
        providers.keySet().stream().sorted().forEach(providerId -> {
            ProviderState ps = providers.get(providerId);
            if (ps != null) {
                synchronized (ps) {
                    snapshot.put(providerId, ps.state);
                }
            }
        });
        return snapshot;
    }

    private Settings settings() {
        Settings settings = settingsSupplier != null ? settingsSupplier.get() : null;
        if (settings == null) {
            return new Settings(5, 60L, 120L);
        }
        return new Settings(
                positive(settings.failureThreshold(), 5),
                positive(settings.windowSeconds(), 60L),
                positive(settings.openDurationSeconds(), 120L));
    }

    private static Settings settingsFrom(RuntimeConfig.ResilienceConfig config) {
        RuntimeConfig.ResilienceConfig defaults = new RuntimeConfig.ResilienceConfig();
        RuntimeConfig.ResilienceConfig source = config != null ? config : defaults;
        return new Settings(
                positive(source.getCircuitBreakerFailureThreshold(), defaults.getCircuitBreakerFailureThreshold()),
                positive(source.getCircuitBreakerWindowSeconds(), defaults.getCircuitBreakerWindowSeconds()),
                positive(source.getCircuitBreakerOpenDurationSeconds(),
                        defaults.getCircuitBreakerOpenDurationSeconds()));
    }

    private static int positive(Integer value, int fallback) {
        return value != null && value > 0 ? value : fallback;
    }

    private static long positive(Long value, long fallback) {
        return value != null && value > 0L ? value : fallback;
    }

    private record Settings(int failureThreshold, long windowSeconds, long openDurationSeconds) {
    }
}
