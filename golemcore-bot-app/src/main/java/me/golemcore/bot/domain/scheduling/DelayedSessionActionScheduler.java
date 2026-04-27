package me.golemcore.bot.domain.scheduling;

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

import me.golemcore.bot.domain.service.RuntimeConfigService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.model.DelayedSessionAction;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Background scheduler for durable delayed session actions.
 */
@Component
@Slf4j
public class DelayedSessionActionScheduler {

    private static final int MAX_LEASES_PER_TICK = 10;

    private final DelayedSessionActionService delayedActionService;
    private final DelayedActionDispatcher delayedActionDispatcher;
    private final RuntimeConfigService runtimeConfigService;
    private final Clock clock;
    private final AtomicBoolean ticking = new AtomicBoolean(false);

    private ScheduledExecutorService scheduler;
    private ExecutorService dispatchExecutor;
    private ScheduledFuture<?> tickTask;

    public DelayedSessionActionScheduler(DelayedSessionActionService delayedActionService,
            DelayedActionDispatcher delayedActionDispatcher,
            RuntimeConfigService runtimeConfigService,
            Clock clock) {
        this.delayedActionService = delayedActionService;
        this.delayedActionDispatcher = delayedActionDispatcher;
        this.runtimeConfigService = runtimeConfigService;
        this.clock = clock;
    }

    @PostConstruct
    public void init() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "delayed-actions-scheduler");
            thread.setDaemon(true);
            return thread;
        });
        dispatchExecutor = Executors.newFixedThreadPool(2, r -> {
            Thread thread = new Thread(r, "delayed-actions-dispatch");
            thread.setDaemon(true);
            return thread;
        });
        long tickSeconds = Math.max(1, runtimeConfigService.getDelayedActionsTickSeconds());
        tickTask = scheduler.scheduleAtFixedRate(this::tick, tickSeconds, tickSeconds, TimeUnit.SECONDS);
        log.info("[DelayedActions] Scheduler started with tick interval: {}s", tickSeconds);
    }

    @PreDestroy
    public void shutdown() {
        if (tickTask != null) {
            tickTask.cancel(false);
        }
        if (scheduler != null) {
            scheduler.shutdown();
        }
        if (dispatchExecutor != null) {
            dispatchExecutor.shutdown();
        }
    }

    void tick() {
        if (!runtimeConfigService.isDelayedActionsEnabled()) {
            return;
        }
        if (!ticking.compareAndSet(false, true)) {
            return;
        }
        try {
            List<DelayedSessionAction> dueActions = delayedActionService.leaseDueActions(MAX_LEASES_PER_TICK);
            for (DelayedSessionAction action : dueActions) {
                dispatchExecutor.execute(() -> dispatch(action));
            }
        } catch (RuntimeException e) {
            log.warn("[DelayedActions] Tick failed: {}", e.getMessage());
        } finally {
            ticking.set(false);
        }
    }

    private void dispatch(DelayedSessionAction action) {
        delayedActionDispatcher.dispatch(action)
                .whenComplete((result, failure) -> {
                    if (failure != null) {
                        handleRetryOrDeadLetter(action, "Dispatch failure: " + failure.getMessage());
                        return;
                    }
                    if (result == null) {
                        handleRetryOrDeadLetter(action, "Dispatch returned no result");
                        return;
                    }
                    if (result.success()) {
                        delayedActionService.markCompleted(action.getId());
                        return;
                    }
                    if (result.retryable()) {
                        handleRetryOrDeadLetter(action, result.error());
                        return;
                    }
                    delayedActionService.markDeadLetter(action.getId(), result.error());
                });
    }

    private void handleRetryOrDeadLetter(DelayedSessionAction action, String error) {
        int nextAttemptNumber = action.getAttempts() + 1;
        if (nextAttemptNumber >= action.getMaxAttempts()) {
            delayedActionService.markDeadLetter(action.getId(), error);
            return;
        }
        Instant retryAt = clock.instant().plus(resolveRetryDelay(nextAttemptNumber));
        delayedActionService.rescheduleRetry(action.getId(), retryAt, error);
    }

    private Duration resolveRetryDelay(int attemptNumber) {
        return switch (attemptNumber) {
        case 1 -> Duration.ofSeconds(30);
        case 2 -> Duration.ofMinutes(2);
        case 3 -> Duration.ofMinutes(10);
        default -> Duration.ofHours(1);
        };
    }
}
