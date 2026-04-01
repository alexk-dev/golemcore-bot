package me.golemcore.bot.domain.service;

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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.model.selfevolving.tactic.TacticRecord;
import me.golemcore.bot.port.outbound.StoragePort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Stores retrievable tactics separately from curated skills under
 * {@code self-evolving/tactics}.
 */
@Service
@Slf4j
public class TacticRecordService {

    private static final String SELF_EVOLVING_DIR = "self-evolving";
    private static final String TACTICS_PREFIX = "tactics/";
    private static final String TACTICS_LIST_PREFIX = "tactics";

    private final StoragePort storagePort;
    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final AtomicReference<List<TacticRecord>> cache = new AtomicReference<>();

    @Autowired
    public TacticRecordService(StoragePort storagePort, Clock clock) {
        this.storagePort = storagePort;
        this.clock = clock;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    public TacticRecordService(StoragePort storagePort) {
        this(storagePort, Clock.systemUTC());
    }

    public TacticRecord save(TacticRecord record) {
        TacticRecord normalized = normalize(record);
        try {
            String path = TACTICS_PREFIX + normalized.getTacticId() + ".json";
            storagePort.putText(SELF_EVOLVING_DIR, path, objectMapper.writeValueAsString(normalized)).join();
            upsertCache(normalized);
            return normalized;
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to serialize tactic record", exception);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Failed to persist tactic record", exception);
        }
    }

    public List<TacticRecord> getAll() {
        List<TacticRecord> cached = cache.get();
        if (cached == null) {
            cached = loadAll();
            cache.set(cached);
        }
        return new ArrayList<>(cached);
    }

    private List<TacticRecord> loadAll() {
        List<String> paths;
        try {
            paths = storagePort.listObjects(SELF_EVOLVING_DIR, TACTICS_LIST_PREFIX).join();
        } catch (RuntimeException exception) {
            log.debug("[TacticSearch] Failed to list tactics: {}", exception.getMessage());
            return new ArrayList<>();
        }

        List<TacticRecord> records = new ArrayList<>();
        for (String path : paths) {
            if (StringValueSupport.isBlank(path) || !path.startsWith(TACTICS_PREFIX) || !path.endsWith(".json")) {
                continue;
            }
            try {
                String json = storagePort.getText(SELF_EVOLVING_DIR, path).join();
                if (StringValueSupport.isBlank(json)) {
                    continue;
                }
                TacticRecord record = objectMapper.readValue(json, TacticRecord.class);
                if (record != null) {
                    records.add(normalize(record));
                }
            } catch (IOException | RuntimeException exception) { // NOSONAR - best-effort storage read
                log.debug("[TacticSearch] Failed to load tactic '{}': {}", path, exception.getMessage());
            }
        }
        records.sort(Comparator.comparing(TacticRecord::getUpdatedAt,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return records;
    }

    private void upsertCache(TacticRecord record) {
        List<TacticRecord> current = cache.get();
        if (current == null) {
            return;
        }
        List<TacticRecord> updated = new ArrayList<>(current);
        updated.removeIf(existing -> existing != null && record.getTacticId().equals(existing.getTacticId()));
        updated.add(record);
        updated.sort(Comparator.comparing(TacticRecord::getUpdatedAt,
                Comparator.nullsLast(Comparator.reverseOrder())));
        cache.set(updated);
    }

    private TacticRecord normalize(TacticRecord record) {
        TacticRecord normalized = record != null ? record : new TacticRecord();
        Instant updatedAt = normalized.getUpdatedAt() != null ? normalized.getUpdatedAt() : Instant.now(clock);

        String tacticId = firstNonBlank(normalized.getTacticId(), normalized.getContentRevisionId(),
                UUID.randomUUID().toString());
        String artifactStreamId = firstNonBlank(normalized.getArtifactStreamId(), tacticId);
        String artifactKey = firstNonBlank(normalized.getArtifactKey(), "tactic:" + tacticId);
        String artifactType = firstNonBlank(normalized.getArtifactType(), "skill");
        String title = firstNonBlank(normalized.getTitle(), humanizeArtifactKey(artifactKey));
        List<String> aliases = normalized.getAliases() != null && !normalized.getAliases().isEmpty()
                ? new ArrayList<>(normalized.getAliases())
                : new ArrayList<>(List.of(artifactKey));

        normalized.setTacticId(tacticId);
        normalized.setArtifactStreamId(artifactStreamId);
        normalized.setOriginArtifactStreamId(firstNonBlank(normalized.getOriginArtifactStreamId(), artifactStreamId));
        normalized.setArtifactKey(artifactKey);
        normalized.setArtifactType(artifactType);
        normalized.setTitle(title);
        normalized.setAliases(aliases);
        normalized.setContentRevisionId(firstNonBlank(normalized.getContentRevisionId(), tacticId));
        normalized.setPromotionState(firstNonBlank(normalized.getPromotionState(), "candidate"));
        normalized.setRolloutStage(firstNonBlank(normalized.getRolloutStage(), "proposed"));
        normalized.setSuccessRate(normalized.getSuccessRate() != null ? normalized.getSuccessRate() : 0.0d);
        normalized.setBenchmarkWinRate(normalized.getBenchmarkWinRate() != null ? normalized.getBenchmarkWinRate()
                : 0.0d);
        normalized.setRegressionFlags(
                normalized.getRegressionFlags() != null ? new ArrayList<>(normalized.getRegressionFlags())
                        : new ArrayList<>());
        normalized.setTaskFamilies(normalized.getTaskFamilies() != null ? new ArrayList<>(normalized.getTaskFamilies())
                : new ArrayList<>(List.of(artifactType)));
        normalized.setTags(normalized.getTags() != null ? new ArrayList<>(normalized.getTags())
                : new ArrayList<>(List.of(artifactType, normalized.getPromotionState())));
        normalized.setEvidenceSnippets(normalized.getEvidenceSnippets() != null
                ? new ArrayList<>(normalized.getEvidenceSnippets())
                : new ArrayList<>());
        normalized.setRecencyScore(normalized.getRecencyScore() != null ? normalized.getRecencyScore() : 1.0d);
        normalized.setGolemLocalUsageSuccess(normalized.getGolemLocalUsageSuccess() != null
                ? normalized.getGolemLocalUsageSuccess()
                : normalized.getSuccessRate());
        normalized.setEmbeddingStatus(firstNonBlank(normalized.getEmbeddingStatus(), "pending"));
        normalized.setUpdatedAt(updatedAt);
        return normalized;
    }

    private String humanizeArtifactKey(String artifactKey) {
        if (StringValueSupport.isBlank(artifactKey)) {
            return "Tactic";
        }
        String normalized = artifactKey.replace(':', ' ').replace('_', ' ').trim();
        if (normalized.isEmpty()) {
            return "Tactic";
        }
        return Character.toUpperCase(normalized.charAt(0)) + normalized.substring(1);
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (!StringValueSupport.isBlank(value)) {
                return value.trim();
            }
        }
        return null;
    }
}
