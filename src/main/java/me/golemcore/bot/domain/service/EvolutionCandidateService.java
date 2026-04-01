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
import me.golemcore.bot.domain.model.selfevolving.EvolutionCandidate;
import me.golemcore.bot.domain.model.selfevolving.RunRecord;
import me.golemcore.bot.domain.model.selfevolving.RunVerdict;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactRevisionRecord;
import me.golemcore.bot.domain.model.selfevolving.tactic.TacticRecord;
import me.golemcore.bot.port.outbound.StoragePort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Produces first-pass evolution candidates from judged run evidence and writes
 * immutable artifact revisions for every proposed change.
 */
@Service
@Slf4j
public class EvolutionCandidateService {

    private static final String SELF_EVOLVING_DIR = "self-evolving";
    private static final String ARTIFACT_REVISIONS_FILE = "artifact-revisions.json";
    private static final TypeReference<List<ArtifactRevisionRecord>> ARTIFACT_REVISION_LIST_TYPE = new TypeReference<>() {
    };

    private final StoragePort storagePort;
    private final TacticRecordService tacticRecordService;
    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final AtomicReference<List<ArtifactRevisionRecord>> artifactRevisionCache = new AtomicReference<>();

    public EvolutionCandidateService(StoragePort storagePort) {
        this(storagePort, new TacticRecordService(storagePort, Clock.systemUTC()), Clock.systemUTC());
    }

    EvolutionCandidateService(StoragePort storagePort, Clock clock) {
        this(storagePort, new TacticRecordService(storagePort, clock), clock);
    }

    @Autowired
    public EvolutionCandidateService(StoragePort storagePort, TacticRecordService tacticRecordService, Clock clock) {
        this.storagePort = storagePort;
        this.tacticRecordService = tacticRecordService;
        this.clock = clock;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    public List<EvolutionCandidate> deriveCandidates(RunRecord runRecord, RunVerdict runVerdict) {
        if (runRecord == null
                || runVerdict == null
                || runVerdict.getEvidenceRefs() == null
                || runVerdict.getEvidenceRefs().isEmpty()) {
            return List.of();
        }

        if ("FAILED".equals(runVerdict.getOutcomeStatus())) {
            return List.of(buildCandidate(
                    runRecord,
                    runVerdict,
                    "fix",
                    resolveFixArtifactType(runVerdict),
                    "Reduce the failure mode observed in this run",
                    "high"));
        }

        if ("COMPLETED".equals(runVerdict.getOutcomeStatus())
                && "CLEAN".equals(runVerdict.getProcessStatus())
                && (runVerdict.getConfidence() == null || runVerdict.getConfidence() >= 0.8)) {
            return List.of(buildCandidate(
                    runRecord,
                    runVerdict,
                    "derive",
                    "skill",
                    "Capture a reusable high-signal pattern from a successful run",
                    "medium"));
        }

        return List.of();
    }

    public EvolutionCandidate ensureArtifactIdentity(EvolutionCandidate candidate) {
        if (candidate == null) {
            return null;
        }
        normalizeCandidate(candidate);
        ensureArtifactRevision(candidate);
        emitTacticRecord(candidate);
        return candidate;
    }

    public List<ArtifactRevisionRecord> getArtifactRevisionRecords() {
        List<ArtifactRevisionRecord> cached = artifactRevisionCache.get();
        if (cached == null) {
            cached = loadArtifactRevisions();
            artifactRevisionCache.set(cached);
        }
        return cached;
    }

    private EvolutionCandidate buildCandidate(
            RunRecord runRecord,
            RunVerdict runVerdict,
            String goal,
            String artifactType,
            String expectedImpact,
            String riskLevel) {
        Instant createdAt = Instant.now(clock);
        EvolutionCandidate candidate = EvolutionCandidate.builder()
                .id(UUID.randomUUID().toString())
                .golemId(runRecord.getGolemId())
                .goal(goal)
                .artifactType(artifactType)
                .baseVersion(runRecord.getArtifactBundleId())
                .proposedDiff(buildProposedDiff(goal, artifactType))
                .expectedImpact(expectedImpact)
                .riskLevel(riskLevel)
                .status("proposed")
                .lifecycleState("candidate")
                .rolloutStage("proposed")
                .createdAt(createdAt)
                .sourceRunIds(List.of(runRecord.getId()))
                .evidenceRefs(runVerdict.getEvidenceRefs())
                .build();
        normalizeCandidate(candidate);
        ensureArtifactRevision(candidate);
        emitTacticRecord(candidate);
        return candidate;
    }

    private void normalizeCandidate(EvolutionCandidate candidate) {
        String artifactType = resolveCanonicalArtifactType(candidate.getArtifactType());
        String artifactSubtype = resolveCanonicalArtifactSubtype(artifactType, candidate.getArtifactSubtype());
        String artifactKey = resolveArtifactKey(artifactType, artifactSubtype, candidate.getArtifactKey());
        ArtifactRevisionRecord latestRevision = findLatestRevision(artifactKey, artifactType, artifactSubtype);

        candidate.setArtifactType(artifactType);
        candidate.setArtifactSubtype(artifactSubtype);
        candidate.setArtifactKey(artifactKey);
        if (candidate.getArtifactAliases() == null || candidate.getArtifactAliases().isEmpty()) {
            candidate.setArtifactAliases(List.of(artifactKey));
        }
        if (StringValueSupport.isBlank(candidate.getArtifactStreamId())) {
            candidate.setArtifactStreamId(
                    latestRevision != null ? latestRevision.getArtifactStreamId() : UUID.randomUUID().toString());
        }
        if (StringValueSupport.isBlank(candidate.getOriginArtifactStreamId())) {
            candidate.setOriginArtifactStreamId(
                    latestRevision != null && !StringValueSupport.isBlank(latestRevision.getOriginArtifactStreamId())
                            ? latestRevision.getOriginArtifactStreamId()
                            : candidate.getArtifactStreamId());
        }
        if (StringValueSupport.isBlank(candidate.getBaseContentRevisionId()) && latestRevision != null) {
            candidate.setBaseContentRevisionId(latestRevision.getContentRevisionId());
        }
        if (StringValueSupport.isBlank(candidate.getContentRevisionId())) {
            candidate.setContentRevisionId(UUID.randomUUID().toString());
        }
        if (StringValueSupport.isBlank(candidate.getLifecycleState())) {
            candidate.setLifecycleState(resolveLifecycleState(candidate.getStatus()));
        }
        if (StringValueSupport.isBlank(candidate.getRolloutStage())) {
            candidate.setRolloutStage(resolveRolloutStage(candidate.getStatus()));
        }
        if (StringValueSupport.isBlank(candidate.getStatus())) {
            candidate.setStatus(resolveLegacyStatus(candidate.getLifecycleState(), candidate.getRolloutStage()));
        }
    }

    private void ensureArtifactRevision(EvolutionCandidate candidate) {
        if (candidate == null || StringValueSupport.isBlank(candidate.getContentRevisionId())) {
            return;
        }
        List<ArtifactRevisionRecord> records = new ArrayList<>(getArtifactRevisionRecords());
        for (ArtifactRevisionRecord existing : records) {
            if (existing != null && candidate.getContentRevisionId().equals(existing.getContentRevisionId())) {
                return;
            }
        }
        ArtifactRevisionRecord record = ArtifactRevisionRecord.builder()
                .artifactStreamId(candidate.getArtifactStreamId())
                .originArtifactStreamId(candidate.getOriginArtifactStreamId())
                .artifactKey(candidate.getArtifactKey())
                .artifactType(candidate.getArtifactType())
                .artifactSubtype(candidate.getArtifactSubtype())
                .contentRevisionId(candidate.getContentRevisionId())
                .baseContentRevisionId(candidate.getBaseContentRevisionId())
                .rawContent(candidate.getProposedDiff())
                .sourceRunIds(candidate.getSourceRunIds() != null ? new ArrayList<>(candidate.getSourceRunIds())
                        : new ArrayList<>())
                .createdAt(candidate.getCreatedAt() != null ? candidate.getCreatedAt() : Instant.now(clock))
                .build();
        records.add(record);
        saveArtifactRevisions(records);
    }

    private ArtifactRevisionRecord findLatestRevision(String artifactKey, String artifactType, String artifactSubtype) {
        ArtifactRevisionRecord latest = null;
        for (ArtifactRevisionRecord record : getArtifactRevisionRecords()) {
            if (record == null) {
                continue;
            }
            if (!artifactKey.equals(record.getArtifactKey())) {
                continue;
            }
            if (!artifactType.equals(record.getArtifactType())) {
                continue;
            }
            if (!artifactSubtype.equals(record.getArtifactSubtype())) {
                continue;
            }
            if (latest == null) {
                latest = record;
                continue;
            }
            Instant latestCreatedAt = latest.getCreatedAt();
            Instant recordCreatedAt = record.getCreatedAt();
            if (latestCreatedAt == null || (recordCreatedAt != null && recordCreatedAt.isAfter(latestCreatedAt))) {
                latest = record;
            }
        }
        return latest;
    }

    private void emitTacticRecord(EvolutionCandidate candidate) {
        if (candidate == null) {
            return;
        }
        tacticRecordService.save(TacticRecord.builder()
                .tacticId(candidate.getContentRevisionId())
                .artifactStreamId(candidate.getArtifactStreamId())
                .originArtifactStreamId(candidate.getOriginArtifactStreamId())
                .artifactKey(candidate.getArtifactKey())
                .artifactType(candidate.getArtifactType())
                .title(resolveTacticTitle(candidate))
                .aliases(candidate.getArtifactAliases() != null ? new ArrayList<>(candidate.getArtifactAliases())
                        : new ArrayList<>())
                .contentRevisionId(candidate.getContentRevisionId())
                .intentSummary(candidate.getExpectedImpact())
                .behaviorSummary(candidate.getProposedDiff())
                .outcomeSummary(candidate.getGoal())
                .evidenceSnippets(resolveEvidenceSnippets(candidate))
                .taskFamilies(resolveTaskFamilies(candidate))
                .tags(resolveTags(candidate))
                .promotionState(candidate.getLifecycleState())
                .rolloutStage(candidate.getRolloutStage())
                .successRate(resolveSuccessRate(candidate))
                .golemLocalUsageSuccess(resolveSuccessRate(candidate))
                .embeddingStatus("pending")
                .updatedAt(candidate.getCreatedAt() != null ? candidate.getCreatedAt() : Instant.now(clock))
                .build());
    }

    private List<ArtifactRevisionRecord> loadArtifactRevisions() {
        try {
            String json = storagePort.getText(SELF_EVOLVING_DIR, ARTIFACT_REVISIONS_FILE).join();
            if (StringValueSupport.isBlank(json)) {
                return new ArrayList<>();
            }
            List<ArtifactRevisionRecord> records = objectMapper.readValue(json, ARTIFACT_REVISION_LIST_TYPE);
            return records != null ? new ArrayList<>(records) : new ArrayList<>();
        } catch (IOException | RuntimeException exception) { // NOSONAR - storage fallback
            log.debug("[SelfEvolving] Failed to load artifact revisions: {}", exception.getMessage());
            return new ArrayList<>();
        }
    }

    private void saveArtifactRevisions(List<ArtifactRevisionRecord> records) {
        try {
            String json = objectMapper.writeValueAsString(records);
            storagePort.putText(SELF_EVOLVING_DIR, ARTIFACT_REVISIONS_FILE, json).join();
            artifactRevisionCache.set(new ArrayList<>(records));
        } catch (Exception exception) { // NOSONAR - storage failure becomes runtime error
            throw new IllegalStateException("Failed to persist artifact revisions", exception);
        }
    }

    private String resolveFixArtifactType(RunVerdict runVerdict) {
        List<String> findings = runVerdict.getProcessFindings();
        if (findings != null) {
            for (String finding : findings) {
                if (finding == null) {
                    continue;
                }
                if (finding.startsWith("tool_error") || finding.contains("tool_")) {
                    return "tool_policy";
                }
                if (finding.contains("skill")) {
                    return "skill";
                }
                if (finding.contains("tier")) {
                    return "routing_policy";
                }
            }
        }
        return "prompt";
    }

    private String buildProposedDiff(String goal, String artifactType) {
        return "selfevolving:" + goal + ":" + artifactType;
    }

    private String resolveCanonicalArtifactType(String artifactType) {
        if (StringValueSupport.isBlank(artifactType)) {
            return "prompt";
        }
        return switch (artifactType) {
        case "prompt section", "prompt", "prompt:section", "prompt:pack", "prompt:template" -> "prompt";
        case "routing_policy", "routing policy", "tier routing policy" -> "routing_policy";
        case "tool_policy", "tool policy" -> "tool_policy";
        case "memory_policy", "memory policy" -> "memory_policy";
        case "context_policy", "context assembly policy" -> "context_policy";
        case "governance_policy", "approval policy preset" -> "governance_policy";
        default -> artifactType;
        };
    }

    private String resolveCanonicalArtifactSubtype(String artifactType, String artifactSubtype) {
        if (!StringValueSupport.isBlank(artifactSubtype)) {
            return switch (artifactSubtype) {
            case "prompt section" -> "prompt:section";
            case "prompt pack" -> "prompt:pack";
            case "context assembly policy" -> "context_policy:assembly";
            case "approval policy preset" -> "governance_policy:approval";
            default -> artifactSubtype;
            };
        }
        return switch (artifactType) {
        case "prompt" -> "prompt:section";
        case "routing_policy" -> "routing_policy:tier";
        case "tool_policy" -> "tool_policy:usage";
        case "memory_policy" -> "memory_policy:retrieval";
        case "context_policy" -> "context_policy:assembly";
        case "governance_policy" -> "governance_policy:approval";
        default -> artifactType;
        };
    }

    private String resolveArtifactKey(String artifactType, String artifactSubtype, String artifactKey) {
        if (!StringValueSupport.isBlank(artifactKey)) {
            return artifactKey;
        }
        return switch (artifactType) {
        case "skill" -> "skill:default";
        case "prompt" -> artifactSubtype;
        case "routing_policy" -> "routing_policy:tier";
        case "tool_policy" -> "tool_policy:usage";
        case "memory_policy" -> "memory_policy:retrieval";
        case "context_policy" -> "context_policy:assembly";
        case "governance_policy" -> "governance_policy:approval";
        default -> artifactSubtype;
        };
    }

    private String resolveLifecycleState(String status) {
        if (StringValueSupport.isBlank(status)) {
            return "candidate";
        }
        return switch (status) {
        case "approved", "approved_pending" -> "approved";
        case "active" -> "active";
        case "reverted" -> "reverted";
        default -> "candidate";
        };
    }

    private String resolveRolloutStage(String status) {
        if (StringValueSupport.isBlank(status)) {
            return "proposed";
        }
        return switch (status) {
        case "replayed" -> "replayed";
        case "shadowed" -> "shadowed";
        case "canary" -> "canary";
        case "approved", "approved_pending" -> "approved";
        case "active" -> "active";
        case "reverted" -> "reverted";
        default -> "proposed";
        };
    }

    private String resolveLegacyStatus(String lifecycleState, String rolloutStage) {
        if ("approved".equals(lifecycleState) || "approved".equals(rolloutStage)) {
            return "approved_pending";
        }
        if ("active".equals(lifecycleState) || "active".equals(rolloutStage)) {
            return "active";
        }
        if ("reverted".equals(lifecycleState) || "reverted".equals(rolloutStage)) {
            return "reverted";
        }
        if ("shadowed".equals(rolloutStage)) {
            return "shadowed";
        }
        if ("canary".equals(rolloutStage)) {
            return "canary";
        }
        if ("replayed".equals(rolloutStage)) {
            return "replayed";
        }
        return "proposed";
    }

    private String resolveTacticTitle(EvolutionCandidate candidate) {
        if (candidate == null || StringValueSupport.isBlank(candidate.getArtifactKey())) {
            return "Tactic";
        }
        String normalized = candidate.getArtifactKey().replace(':', ' ').replace('_', ' ').trim();
        if (normalized.isEmpty()) {
            return "Tactic";
        }
        return Character.toUpperCase(normalized.charAt(0)) + normalized.substring(1);
    }

    private List<String> resolveEvidenceSnippets(EvolutionCandidate candidate) {
        List<String> snippets = new ArrayList<>();
        if (candidate == null || candidate.getEvidenceRefs() == null) {
            return snippets;
        }
        for (var ref : candidate.getEvidenceRefs()) {
            if (ref == null) {
                continue;
            }
            if (!StringValueSupport.isBlank(ref.getOutputFragment())) {
                snippets.add(ref.getOutputFragment().trim());
                continue;
            }
            if (!StringValueSupport.isBlank(ref.getSpanId())) {
                snippets.add("span:" + ref.getSpanId().trim());
                continue;
            }
            if (!StringValueSupport.isBlank(ref.getTraceId())) {
                snippets.add("trace:" + ref.getTraceId().trim());
            }
        }
        return snippets;
    }

    private List<String> resolveTaskFamilies(EvolutionCandidate candidate) {
        if (candidate == null || StringValueSupport.isBlank(candidate.getArtifactType())) {
            return List.of();
        }
        return List.of(candidate.getArtifactType());
    }

    private List<String> resolveTags(EvolutionCandidate candidate) {
        if (candidate == null) {
            return List.of();
        }
        List<String> tags = new ArrayList<>();
        if (!StringValueSupport.isBlank(candidate.getArtifactType())) {
            tags.add(candidate.getArtifactType());
        }
        if (!StringValueSupport.isBlank(candidate.getLifecycleState())) {
            tags.add(candidate.getLifecycleState());
        }
        if (!StringValueSupport.isBlank(candidate.getGoal())) {
            tags.add(candidate.getGoal());
        }
        return tags;
    }

    private Double resolveSuccessRate(EvolutionCandidate candidate) {
        if (candidate == null) {
            return 0.0d;
        }
        return "derive".equals(candidate.getGoal()) ? 1.0d : 0.0d;
    }
}
