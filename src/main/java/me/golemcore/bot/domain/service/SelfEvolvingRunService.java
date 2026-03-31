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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.FinishReason;
import me.golemcore.bot.domain.model.selfevolving.ArtifactBundleRecord;
import me.golemcore.bot.domain.model.selfevolving.RunRecord;
import me.golemcore.bot.port.outbound.StoragePort;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Stores and updates SelfEvolving run records.
 */
@Service
@Slf4j
public class SelfEvolvingRunService {

    private static final String SELF_EVOLVING_DIR = "self-evolving";
    private static final String RUNS_FILE = "runs.json";
    private static final TypeReference<List<RunRecord>> RUN_LIST_TYPE = new TypeReference<>() {
    };

    private final StoragePort storagePort;
    private final ArtifactBundleService artifactBundleService;
    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final AtomicReference<List<RunRecord>> runCache = new AtomicReference<>();

    public SelfEvolvingRunService(StoragePort storagePort, ArtifactBundleService artifactBundleService) {
        this(storagePort, artifactBundleService, Clock.systemUTC());
    }

    SelfEvolvingRunService(StoragePort storagePort, ArtifactBundleService artifactBundleService, Clock clock) {
        this.storagePort = storagePort;
        this.artifactBundleService = artifactBundleService;
        this.clock = clock;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    public RunRecord startRun(AgentContext context) {
        ArtifactBundleRecord bundle = artifactBundleService.snapshot(context);
        RunRecord run = RunRecord.builder()
                .id(UUID.randomUUID().toString())
                .golemId(resolveGolemId(context))
                .sessionId(context.getSession() != null ? context.getSession().getId() : null)
                .traceId(context.getTraceContext() != null ? context.getTraceContext().getTraceId() : null)
                .artifactBundleId(bundle.getId())
                .status("RUNNING")
                .startedAt(Instant.now(clock))
                .build();
        save(run);
        return run;
    }

    public Optional<RunRecord> findRun(String runId) {
        if (StringValueSupport.isBlank(runId)) {
            return Optional.empty();
        }
        return getRuns().stream()
                .filter(run -> run != null && runId.equals(run.getId()))
                .findFirst();
    }

    public RunRecord completeRun(RunRecord run, AgentContext context) {
        if (run == null) {
            return null;
        }
        ArtifactBundleRecord refreshedBundle = artifactBundleService.refresh(run.getArtifactBundleId(), context);
        run.setTraceId(context.getTraceContext() != null ? context.getTraceContext().getTraceId() : run.getTraceId());
        run.setArtifactBundleId(refreshedBundle != null ? refreshedBundle.getId() : run.getArtifactBundleId());
        run.setCompletedAt(Instant.now(clock));
        run.setStatus(resolveCompletionStatus(context));
        save(run);
        return run;
    }

    public List<RunRecord> getRuns() {
        List<RunRecord> cached = runCache.get();
        if (cached == null) {
            cached = loadRuns();
            runCache.set(cached);
        }
        return cached;
    }

    public String exportRunsJson() {
        try {
            return objectMapper.writeValueAsString(getRuns());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to serialize runs", e);
        }
    }

    private void save(RunRecord run) {
        List<RunRecord> runs = new ArrayList<>(getRuns());
        boolean updated = false;
        for (int i = 0; i < runs.size(); i++) {
            RunRecord existing = runs.get(i);
            if (existing != null && run.getId().equals(existing.getId())) {
                runs.set(i, run);
                updated = true;
                break;
            }
        }
        if (!updated) {
            runs.add(run);
        }
        saveRuns(runs);
    }

    private List<RunRecord> loadRuns() {
        try {
            String json = storagePort.getText(SELF_EVOLVING_DIR, RUNS_FILE).join();
            if (StringValueSupport.isBlank(json)) {
                return new ArrayList<>();
            }
            List<RunRecord> runs = objectMapper.readValue(json, RUN_LIST_TYPE);
            return runs != null ? new ArrayList<>(runs) : new ArrayList<>();
        } catch (IOException | RuntimeException e) { // NOSONAR - storage fallback
            log.debug("[SelfEvolving] Failed to load runs: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    private void saveRuns(List<RunRecord> runs) {
        try {
            String json = objectMapper.writeValueAsString(runs);
            storagePort.putText(SELF_EVOLVING_DIR, RUNS_FILE, json).join();
            runCache.set(new ArrayList<>(runs));
        } catch (Exception e) { // NOSONAR - storage failure becomes runtime error
            throw new IllegalStateException("Failed to persist self-evolving runs", e);
        }
    }

    private String resolveCompletionStatus(AgentContext context) {
        if (context == null || context.getTurnOutcome() == null || context.getTurnOutcome().getFinishReason() == null) {
            return "COMPLETED";
        }
        FinishReason finishReason = context.getTurnOutcome().getFinishReason();
        return finishReason == FinishReason.SUCCESS ? "COMPLETED" : "FAILED";
    }

    private String resolveGolemId(AgentContext context) {
        String golemId = context.getAttribute(ContextAttributes.HIVE_GOLEM_ID);
        if (!StringValueSupport.isBlank(golemId)) {
            return golemId;
        }
        if (context.getSession() != null && context.getSession().getMetadata() != null) {
            Object metadataValue = context.getSession().getMetadata().get(ContextAttributes.HIVE_GOLEM_ID);
            if (metadataValue instanceof String stringValue && !stringValue.isBlank()) {
                return stringValue;
            }
        }
        if (context.getSession() != null && !StringValueSupport.isBlank(context.getSession().getId())) {
            return "local-" + context.getSession().getId();
        }
        return "local-golem";
    }
}
