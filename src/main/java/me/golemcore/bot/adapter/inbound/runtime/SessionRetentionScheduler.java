package me.golemcore.bot.adapter.inbound.runtime;

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

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.domain.service.SessionRetentionCleanupService;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class SessionRetentionScheduler {

    private static final Duration TICK_INTERVAL = Duration.ofMinutes(1);

    private final SessionRetentionCleanupService sessionRetentionCleanupService;
    private final RuntimeConfigService runtimeConfigService;
    private final Clock clock;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<Instant> lastRunAt = new AtomicReference<>();

    private ScheduledExecutorService scheduler;

    public SessionRetentionScheduler(SessionRetentionCleanupService sessionRetentionCleanupService,
            RuntimeConfigService runtimeConfigService,
            Clock clock) {
        this.sessionRetentionCleanupService = sessionRetentionCleanupService;
        this.runtimeConfigService = runtimeConfigService;
        this.clock = clock;
    }

    @PostConstruct
    public void init() {
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "session-retention-scheduler");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleAtFixedRate(this::tick,
                TICK_INTERVAL.toSeconds(),
                TICK_INTERVAL.toSeconds(),
                TimeUnit.SECONDS);
        tick();
        log.info("[SessionRetention] Scheduler started with tick interval: {}s", TICK_INTERVAL.toSeconds());
    }

    @PreDestroy
    public void shutdown() {
        if (scheduler == null) {
            return;
        }
        scheduler.shutdownNow();
    }

    void tick() {
        if (!runtimeConfigService.isSessionRetentionEnabled()) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            Instant now = clock.instant();
            Instant previousRun = lastRunAt.get();
            Duration cleanupInterval = runtimeConfigService.getSessionRetentionCleanupInterval();
            if (previousRun != null && previousRun.plus(cleanupInterval).isAfter(now)) {
                return;
            }
            sessionRetentionCleanupService.cleanupExpiredSessions();
            lastRunAt.set(now);
        } catch (RuntimeException exception) {
            log.warn("[SessionRetention] Cleanup tick failed: {}", exception.getMessage());
        } finally {
            running.set(false);
        }
    }
}
