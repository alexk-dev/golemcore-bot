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

import me.golemcore.bot.domain.model.selfevolving.tactic.TacticIndexDocument;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Rebuilds tactic-search lexical documents using atomic snapshot replacement.
 */
@Service
public class TacticIndexRebuildService {

    private final TacticRecordService tacticRecordService;
    private final TacticSearchDocumentAssembler documentAssembler;
    private final TacticBm25IndexService bm25IndexService;
    private final TacticEmbeddingIndexService tacticEmbeddingIndexService;
    private final Clock clock;
    private final AtomicReference<Snapshot> rebuildSnapshot = new AtomicReference<>(
            new Snapshot(0, Instant.EPOCH, List.of()));

    public TacticIndexRebuildService(
            TacticRecordService tacticRecordService,
            TacticSearchDocumentAssembler documentAssembler,
            TacticBm25IndexService bm25IndexService,
            TacticEmbeddingIndexService tacticEmbeddingIndexService,
            Clock clock) {
        this.tacticRecordService = tacticRecordService;
        this.documentAssembler = documentAssembler;
        this.bm25IndexService = bm25IndexService;
        this.tacticEmbeddingIndexService = tacticEmbeddingIndexService;
        this.clock = clock;
    }

    public void rebuildAll() {
        rebuild("full");
    }

    public void onTacticChanged(String tacticId) {
        rebuild("tactic:" + tacticId);
    }

    public void onPromotionStateChanged(String artifactStreamId) {
        rebuild("promotion:" + artifactStreamId);
    }

    public void onBenchmarkChanged(String artifactStreamId) {
        rebuild("benchmark:" + artifactStreamId);
    }

    public void onRegressionChanged(String artifactStreamId) {
        rebuild("regression:" + artifactStreamId);
    }

    public void onApprovalNotesChanged(String artifactStreamId) {
        rebuild("approval:" + artifactStreamId);
    }

    public Snapshot snapshot() {
        return rebuildSnapshot.get();
    }

    private void rebuild(String trigger) {
        List<TacticIndexDocument> documents = tacticRecordService.getAll().stream()
                .map(documentAssembler::assemble)
                .toList();
        bm25IndexService.replaceDocuments(documents);
        if (tacticEmbeddingIndexService != null) {
            tacticEmbeddingIndexService.rebuildAll();
        }
        Snapshot previous = snapshot();
        rebuildSnapshot.set(new Snapshot(previous.rebuildCount() + 1, Instant.now(clock), documents));
    }

    public record Snapshot(int rebuildCount, Instant rebuiltAt, List<TacticIndexDocument> documents) {
    }
}
