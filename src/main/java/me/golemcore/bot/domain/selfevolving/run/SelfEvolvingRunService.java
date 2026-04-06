package me.golemcore.bot.domain.selfevolving.run;

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

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.FinishReason;
import me.golemcore.bot.domain.model.selfevolving.ArtifactBundleRecord;
import me.golemcore.bot.domain.model.selfevolving.RunRecord;
import me.golemcore.bot.domain.model.selfevolving.RunVerdict;
import me.golemcore.bot.domain.selfevolving.artifact.ArtifactBundleService;
import me.golemcore.bot.domain.service.StringValueSupport;
import me.golemcore.bot.port.outbound.selfevolving.RunJournalPort;
import org.springframework.stereotype.Service;

/**
 * Stores and updates SelfEvolving run records.
 */
@Service
@Slf4j
public class SelfEvolvingRunService {

    private final RunJournalPort runJournal;
    private final ArtifactBundleService artifactBundleService;
    private final Clock clock;
    private final AtomicReference<List<RunRecord>> runCache = new AtomicReference<>();
    private final AtomicReference<Map<String, RunVerdict>> verdictCache = new AtomicReference<>();

    public SelfEvolvingRunService(RunJournalPort runJournal, ArtifactBundleService artifactBundleService, Clock clock) {
        this.runJournal = runJournal;
        this.artifactBundleService = artifactBundleService;
        this.clock = clock;
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
        run.setArtifactBundleId(refreshedBundle.getId());
        run.setCompletedAt(Instant.now(clock));
        run.setStatus(resolveCompletionStatus(context));
        run.setAppliedTacticIds(resolveAppliedTacticIds(context));
        save(run);
        return run;
    }

    private List<String> resolveAppliedTacticIds(AgentContext context) {
        if (context == null) {
            return new ArrayList<>();
        }
        List<String> applied = context.getAttribute(ContextAttributes.APPLIED_TACTIC_IDS);
        if (applied == null || applied.isEmpty()) {
            return new ArrayList<>();
        }
        List<String> copy = new ArrayList<>();
        for (String id : applied) {
            if (id != null && !id.isBlank()) {
                copy.add(id);
            }
        }
        return copy;
    }

    public Optional<RunVerdict> findVerdict(String runId) {
        if (StringValueSupport.isBlank(runId)) {
            return Optional.empty();
        }
        return Optional.ofNullable(getVerdicts().get(runId));
    }

    public void saveVerdict(String runId, RunVerdict verdict) {
        if (StringValueSupport.isBlank(runId) || verdict == null) {
            return;
        }
        verdict.setRunId(runId);
        Map<String, RunVerdict> verdicts = new LinkedHashMap<>(getVerdicts());
        verdicts.put(runId, verdict);
        runJournal.saveVerdicts(verdicts);
        verdictCache.set(new LinkedHashMap<>(verdicts));
    }

    public List<RunRecord> getRuns() {
        List<RunRecord> cached = runCache.get();
        if (cached == null) {
            cached = runJournal.loadRuns();
            runCache.set(cached);
        }
        return cached;
    }

    public String exportRunsJson() {
        return runJournal.exportRunsAsJson(getRuns());
    }

    private Map<String, RunVerdict> getVerdicts() {
        Map<String, RunVerdict> cached = verdictCache.get();
        if (cached == null) {
            cached = runJournal.loadVerdicts();
            verdictCache.set(cached);
        }
        return cached;
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
        runJournal.saveRuns(runs);
        runCache.set(new ArrayList<>(runs));
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
