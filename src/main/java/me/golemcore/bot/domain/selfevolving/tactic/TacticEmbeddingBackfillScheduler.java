package me.golemcore.bot.domain.selfevolving.tactic;

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
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.model.selfevolving.tactic.TacticSearchStatus;
import org.springframework.stereotype.Component;

/**
 * Periodically backfills tactic embedding entries that are missing from the
 * persistent vector store.
 */
@Component
@Slf4j
public class TacticEmbeddingBackfillScheduler {

    private static final Duration BACKFILL_INTERVAL = Duration.ofMinutes(30);

    private final TacticEmbeddingIndexService tacticEmbeddingIndexService;
    private final TacticIndexRebuildService tacticIndexRebuildService;
    private final LocalEmbeddingBootstrapService localEmbeddingBootstrapService;
    private final Clock clock;
    private final AtomicBoolean ticking = new AtomicBoolean(false);

    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> tickTask;

    public TacticEmbeddingBackfillScheduler(
            TacticEmbeddingIndexService tacticEmbeddingIndexService,
            TacticIndexRebuildService tacticIndexRebuildService,
            LocalEmbeddingBootstrapService localEmbeddingBootstrapService,
            Clock clock) {
        this.tacticEmbeddingIndexService = tacticEmbeddingIndexService;
        this.tacticIndexRebuildService = tacticIndexRebuildService;
        this.localEmbeddingBootstrapService = localEmbeddingBootstrapService;
        this.clock = clock;
    }

    @PostConstruct
    public void init() {
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "tactic-embedding-backfill");
            thread.setDaemon(true);
            return thread;
        });
        long intervalMinutes = BACKFILL_INTERVAL.toMinutes();
        tickTask = scheduler.scheduleWithFixedDelay(this::tick, intervalMinutes, intervalMinutes, TimeUnit.MINUTES);
        log.info("[TacticSearch] Embedding backfill scheduler started with interval: {}m", intervalMinutes);
    }

    @PreDestroy
    public void shutdown() {
        if (tickTask != null) {
            tickTask.cancel(false);
        }
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }

    void tick() {
        if (!ticking.compareAndSet(false, true)) {
            return;
        }
        try {
            List<String> missingTacticIds = tacticEmbeddingIndexService.findMissingPersistedEntryTacticIds();
            if (missingTacticIds.isEmpty()) {
                return;
            }
            if (!isEmbeddingRuntimeAvailable()) {
                log.debug("[TacticSearch] Skipping embedding backfill at {} because embedding runtime is unavailable",
                        clock.instant());
                return;
            }
            log.info("[TacticSearch] Backfilling embeddings for {} tactics missing persisted vector entries",
                    missingTacticIds.size());
            tacticIndexRebuildService.rebuildAll();
        } catch (RuntimeException exception) { // NOSONAR - scheduler loop must stay alive
            log.warn("[TacticSearch] Embedding backfill tick failed: {}", exception.getMessage());
        } finally {
            ticking.set(false);
        }
    }

    private boolean isEmbeddingRuntimeAvailable() {
        if (localEmbeddingBootstrapService == null) {
            return true;
        }
        TacticSearchStatus status = localEmbeddingBootstrapService.probeStatus();
        if (status == null) {
            return true;
        }
        if (!"ollama".equalsIgnoreCase(status.getProvider())) {
            return true;
        }
        return "hybrid".equalsIgnoreCase(status.getMode())
                && Boolean.TRUE.equals(status.getRuntimeHealthy())
                && Boolean.TRUE.equals(status.getModelAvailable())
                && !Boolean.TRUE.equals(status.getDegraded());
    }
}
