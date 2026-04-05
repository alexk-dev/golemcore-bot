package me.golemcore.bot.domain.selfevolving.artifact;

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
import me.golemcore.bot.domain.model.selfevolving.EvolutionProposal;
import me.golemcore.bot.domain.model.selfevolving.VerdictEvidenceRef;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactRevisionRecord;
import me.golemcore.bot.port.outbound.StoragePort;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import me.golemcore.bot.domain.selfevolving.candidate.CandidateLifecycleResolver;
import me.golemcore.bot.domain.service.StringValueSupport;

/**
 * Resolves canonical artifact identity for evolution candidates and persists
 * the immutable artifact revision lineage they reference.
 */
@Service
@Slf4j
public class EvolutionArtifactIdentityService {

    private static final String SELF_EVOLVING_DIR = "self-evolving";
    private static final String ARTIFACT_REVISIONS_FILE = "artifact-revisions.json";
    private static final Set<String> SEMANTIC_SKILL_KEY_STOPWORDS = Set.of(
            "a",
            "an",
            "and",
            "as",
            "capture",
            "cleanly",
            "demonstrated",
            "document",
            "flow",
            "from",
            "guidance",
            "in",
            "observed",
            "pattern",
            "retries",
            "reusable",
            "run",
            "sequence",
            "successful",
            "tactic",
            "the",
            "this",
            "without");
    private static final TypeReference<List<ArtifactRevisionRecord>> ARTIFACT_REVISION_LIST_TYPE = new TypeReference<>() {
    };

    private final StoragePort storagePort;
    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final AtomicReference<List<ArtifactRevisionRecord>> artifactRevisionCache = new AtomicReference<>();

    public EvolutionArtifactIdentityService(StoragePort storagePort, Clock clock) {
        this.storagePort = storagePort;
        this.clock = clock;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    public EvolutionCandidate ensureArtifactIdentity(EvolutionCandidate candidate) {
        if (candidate == null) {
            return null;
        }
        normalizeCandidate(candidate);
        ensureArtifactRevision(candidate);
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

    private void normalizeCandidate(EvolutionCandidate candidate) {
        if (!StringValueSupport.isBlank(candidate.getArtifactType())
                && !StringValueSupport.isBlank(candidate.getArtifactSubtype())
                && !StringValueSupport.isBlank(candidate.getArtifactKey())
                && !StringValueSupport.isBlank(candidate.getArtifactStreamId())
                && !StringValueSupport.isBlank(candidate.getOriginArtifactStreamId())
                && !StringValueSupport.isBlank(candidate.getContentRevisionId())
                && !StringValueSupport.isBlank(candidate.getLifecycleState())
                && !StringValueSupport.isBlank(candidate.getRolloutStage())
                && !StringValueSupport.isBlank(candidate.getStatus())) {
            return;
        }
        String artifactType = resolveCanonicalArtifactType(candidate.getArtifactType());
        String artifactSubtype = resolveCanonicalArtifactSubtype(artifactType, candidate.getArtifactSubtype());
        String artifactKey = resolveArtifactKey(candidate, artifactType, artifactSubtype, candidate.getArtifactKey());
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
            candidate.setContentRevisionId(computeContentRevisionId(candidate));
        }
        if (StringValueSupport.isBlank(candidate.getLifecycleState())) {
            candidate.setLifecycleState(CandidateLifecycleResolver.resolveLifecycleState(candidate.getStatus()));
        }
        if (StringValueSupport.isBlank(candidate.getRolloutStage())) {
            candidate.setRolloutStage(CandidateLifecycleResolver.resolveRolloutStage(candidate.getStatus()));
        }
        if (StringValueSupport.isBlank(candidate.getStatus())) {
            candidate.setStatus(CandidateLifecycleResolver.resolveLegacyStatus(
                    candidate.getLifecycleState(),
                    candidate.getRolloutStage()));
        }
    }

    private boolean ensureArtifactRevision(EvolutionCandidate candidate) {
        if (candidate == null || StringValueSupport.isBlank(candidate.getContentRevisionId())) {
            return false;
        }
        List<ArtifactRevisionRecord> records = new ArrayList<>(getArtifactRevisionRecords());
        for (ArtifactRevisionRecord existing : records) {
            if (existing != null && candidate.getContentRevisionId().equals(existing.getContentRevisionId())) {
                return false;
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
                .traceIds(resolveEvidenceTraceIds(candidate))
                .spanIds(resolveEvidenceSpanIds(candidate))
                .createdAt(candidate.getCreatedAt() != null ? candidate.getCreatedAt() : Instant.now(clock))
                .build();
        records.add(record);
        saveArtifactRevisions(records);
        return true;
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
            storagePort.putTextAtomic(SELF_EVOLVING_DIR, ARTIFACT_REVISIONS_FILE, json, true).join();
            artifactRevisionCache.set(new ArrayList<>(records));
        } catch (Exception exception) { // NOSONAR - storage failure becomes runtime error
            throw new IllegalStateException("Failed to persist artifact revisions", exception);
        }
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

    private String resolveArtifactKey(
            EvolutionCandidate candidate,
            String artifactType,
            String artifactSubtype,
            String artifactKey) {
        if (!StringValueSupport.isBlank(artifactKey)) {
            return artifactKey;
        }
        return switch (artifactType) {
        case "skill" -> resolveSemanticSkillKey(candidate);
        case "prompt" -> artifactSubtype;
        case "routing_policy" -> "routing_policy:tier";
        case "tool_policy" -> "tool_policy:usage";
        case "memory_policy" -> "memory_policy:retrieval";
        case "context_policy" -> "context_policy:assembly";
        case "governance_policy" -> "governance_policy:approval";
        default -> artifactSubtype;
        };
    }

    private String resolveSemanticSkillKey(EvolutionCandidate candidate) {
        if (candidate == null) {
            return "skill:default";
        }
        EvolutionProposal proposal = candidate.getProposal();
        String semanticSource = firstNonBlank(
                proposal != null ? proposal.getSummary() : null,
                proposal != null ? proposal.getBehaviorInstructions() : null,
                candidate.getExpectedImpact());
        if (StringValueSupport.isBlank(semanticSource) && !isPlaceholderDiff(candidate.getProposedDiff())) {
            semanticSource = candidate.getProposedDiff();
        }
        if (StringValueSupport.isBlank(semanticSource)) {
            return "skill:default";
        }
        String normalized = semanticSource.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .trim();
        if (normalized.isEmpty()) {
            return "skill:default";
        }
        List<String> tokens = new ArrayList<>();
        for (String token : normalized.split("\\s+")) {
            if (token.length() < 3 || SEMANTIC_SKILL_KEY_STOPWORDS.contains(token)) {
                continue;
            }
            tokens.add(token);
            if (tokens.size() == 4) {
                break;
            }
        }
        if (tokens.isEmpty()) {
            return "skill:default";
        }
        return "skill:" + String.join("-", tokens);
    }

    private List<String> resolveEvidenceTraceIds(EvolutionCandidate candidate) {
        Set<String> traceIds = new LinkedHashSet<>();
        if (candidate == null || candidate.getEvidenceRefs() == null) {
            return new ArrayList<>();
        }
        for (VerdictEvidenceRef evidenceRef : candidate.getEvidenceRefs()) {
            if (evidenceRef != null && !StringValueSupport.isBlank(evidenceRef.getTraceId())) {
                traceIds.add(evidenceRef.getTraceId().trim());
            }
        }
        return new ArrayList<>(traceIds);
    }

    private List<String> resolveEvidenceSpanIds(EvolutionCandidate candidate) {
        Set<String> spanIds = new LinkedHashSet<>();
        if (candidate == null || candidate.getEvidenceRefs() == null) {
            return new ArrayList<>();
        }
        for (VerdictEvidenceRef evidenceRef : candidate.getEvidenceRefs()) {
            if (evidenceRef != null && !StringValueSupport.isBlank(evidenceRef.getSpanId())) {
                spanIds.add(evidenceRef.getSpanId().trim());
            }
        }
        return new ArrayList<>(spanIds);
    }

    private boolean isPlaceholderDiff(String proposedDiff) {
        if (StringValueSupport.isBlank(proposedDiff)) {
            return true;
        }
        return proposedDiff.matches("selfevolving:[a-z_]+:[a-z_]+");
    }

    private String computeContentRevisionId(EvolutionCandidate candidate) {
        StringBuilder seed = new StringBuilder();
        appendSeed(seed, candidate.getArtifactKey());
        appendSeed(seed, candidate.getArtifactType());
        appendSeed(seed, candidate.getArtifactSubtype());
        appendSeed(seed, normalizeWhitespace(candidate.getProposedDiff()));
        EvolutionProposal proposal = candidate.getProposal();
        if (proposal != null) {
            appendSeed(seed, normalizeWhitespace(proposal.getSummary()));
            appendSeed(seed, normalizeWhitespace(proposal.getBehaviorInstructions()));
            appendSeed(seed, normalizeWhitespace(proposal.getToolInstructions()));
            appendSeed(seed, normalizeWhitespace(proposal.getExpectedOutcome()));
        }
        if (seed.length() == 0) {
            return UUID.randomUUID().toString();
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(seed.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : bytes) {
                hex.append(String.format(Locale.ROOT, "%02x", b));
            }
            return hex.substring(0, 32);
        } catch (NoSuchAlgorithmException exception) { // NOSONAR - SHA-256 is standard, fallback is defensive
            return UUID.randomUUID().toString();
        }
    }

    private void appendSeed(StringBuilder seed, String value) {
        if (!StringValueSupport.isBlank(value)) {
            if (seed.length() > 0) {
                seed.append('\u0000');
            }
            seed.append(value.trim().toLowerCase(Locale.ROOT));
        }
    }

    private String normalizeWhitespace(String value) {
        return value == null ? null : value.trim().replaceAll("\\s+", " ");
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (!StringValueSupport.isBlank(value)) {
                return value;
            }
        }
        return null;
    }
}
