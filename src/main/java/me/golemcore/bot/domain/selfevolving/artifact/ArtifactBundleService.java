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
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.Skill;
import me.golemcore.bot.domain.model.selfevolving.ArtifactBundleRecord;
import me.golemcore.bot.domain.model.selfevolving.EvolutionCandidate;
import me.golemcore.bot.port.outbound.StoragePort;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.domain.service.StringValueSupport;

/**
 * Captures the runtime artifact bundle used for a SelfEvolving run.
 */
@Service
@Slf4j
public class ArtifactBundleService {

    private static final String SELF_EVOLVING_DIR = "self-evolving";
    private static final String BUNDLES_FILE = "artifact-bundles.json";
    private static final TypeReference<List<ArtifactBundleRecord>> BUNDLE_LIST_TYPE = new TypeReference<>() {
    };

    private final StoragePort storagePort;
    private final RuntimeConfigService runtimeConfigService;
    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final AtomicReference<List<ArtifactBundleRecord>> bundleCache = new AtomicReference<>();

    public ArtifactBundleService(StoragePort storagePort, RuntimeConfigService runtimeConfigService, Clock clock) {
        this.storagePort = storagePort;
        this.runtimeConfigService = runtimeConfigService;
        this.clock = clock;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    public ArtifactBundleRecord snapshot(AgentContext context) {
        ArtifactBundleRecord bundle = buildSnapshot(UUID.randomUUID().toString(), context);
        save(bundle);
        return bundle;
    }

    public ArtifactBundleRecord refresh(String bundleId, AgentContext context) {
        if (StringValueSupport.isBlank(bundleId)) {
            return snapshot(context);
        }
        ArtifactBundleRecord existing = getBundles().stream()
                .filter(bundle -> bundle != null && bundleId.equals(bundle.getId()))
                .findFirst()
                .orElse(null);
        ArtifactBundleRecord updated = buildSnapshot(bundleId, context);
        if (existing != null) {
            updated.setCreatedAt(existing.getCreatedAt());
            updated.setActivatedAt(existing.getActivatedAt());
            updated.setSourceCandidateId(existing.getSourceCandidateId());
            updated.setSourceRunId(existing.getSourceRunId());
            updated.setStatus(existing.getStatus());
            updated.setArtifactRevisionBindings(existing.getArtifactRevisionBindings() != null
                    ? new LinkedHashMap<>(existing.getArtifactRevisionBindings())
                    : new LinkedHashMap<>());
        }
        save(updated);
        return updated;
    }

    public List<ArtifactBundleRecord> getBundles() {
        List<ArtifactBundleRecord> cached = bundleCache.get();
        if (cached == null) {
            cached = loadBundles();
            bundleCache.set(cached);
        }
        return cached;
    }

    public void save(ArtifactBundleRecord bundle) {
        List<ArtifactBundleRecord> bundles = new ArrayList<>(getBundles());
        boolean updated = false;
        for (int index = 0; index < bundles.size(); index++) {
            ArtifactBundleRecord existing = bundles.get(index);
            if (existing != null && bundle.getId().equals(existing.getId())) {
                bundles.set(index, bundle);
                updated = true;
                break;
            }
        }
        if (!updated) {
            bundles.add(bundle);
        }
        saveBundles(bundles);
    }

    public void bindBaseRevisions(String bundleId, List<EvolutionCandidate> candidates) {
        if (StringValueSupport.isBlank(bundleId) || candidates == null || candidates.isEmpty()) {
            return;
        }
        List<ArtifactBundleRecord> bundles = new ArrayList<>(getBundles());
        boolean updated = false;
        for (ArtifactBundleRecord bundle : bundles) {
            if (bundle == null || !bundleId.equals(bundle.getId())) {
                continue;
            }
            updated = bindBaseRevisions(bundle, candidates);
            break;
        }
        if (updated) {
            saveBundles(bundles);
        }
    }

    public ArtifactBundleRecord promoteCandidateBundle(String bundleId, EvolutionCandidate candidate, String status) {
        if (StringValueSupport.isBlank(bundleId) || candidate == null) {
            return null;
        }
        ArtifactBundleRecord baseBundle = getBundles().stream()
                .filter(bundle -> bundle != null && candidate.getBaseVersion() != null)
                .filter(bundle -> candidate.getBaseVersion().equals(bundle.getId()))
                .findFirst()
                .orElse(null);
        ArtifactBundleRecord promotedBundle = copyBundle(baseBundle, bundleId, candidate);
        Map<String, String> bindings = promotedBundle.getArtifactRevisionBindings() != null
                ? new LinkedHashMap<>(promotedBundle.getArtifactRevisionBindings())
                : new LinkedHashMap<>();
        if (!StringValueSupport.isBlank(candidate.getArtifactStreamId())
                && !StringValueSupport.isBlank(candidate.getContentRevisionId())) {
            bindings.put(candidate.getArtifactStreamId(), candidate.getContentRevisionId());
        }
        promotedBundle.setArtifactRevisionBindings(bindings);
        promotedBundle.setStatus(StringValueSupport.isBlank(status) ? "snapshot" : status.trim().toLowerCase());
        promotedBundle.setActivatedAt(Instant.now(clock));
        promotedBundle.setSourceCandidateId(candidate.getId());
        if (candidate.getSourceRunIds() != null && !candidate.getSourceRunIds().isEmpty()) {
            promotedBundle.setSourceRunId(candidate.getSourceRunIds().getFirst());
        }
        save(promotedBundle);
        return promotedBundle;
    }

    private ArtifactBundleRecord buildSnapshot(String bundleId, AgentContext context) {
        return ArtifactBundleRecord.builder()
                .id(bundleId)
                .golemId(resolveGolemId(context))
                .status("SNAPSHOT")
                .createdAt(Instant.now(clock))
                .skillVersions(resolveSkillVersions(context))
                .artifactKeyBindings(resolveArtifactKeyBindings(context))
                .artifactTypeBindings(resolveArtifactTypeBindings(context))
                .artifactSubtypeBindings(resolveArtifactSubtypeBindings(context))
                .tierBindings(resolveTierBindings(context))
                .configSnapshot(buildConfigSnapshot(context))
                .build();
    }

    private boolean bindBaseRevisions(ArtifactBundleRecord bundle, List<EvolutionCandidate> candidates) {
        Map<String, String> bindings = bundle.getArtifactRevisionBindings() != null
                ? new LinkedHashMap<>(bundle.getArtifactRevisionBindings())
                : new LinkedHashMap<>();
        boolean updated = false;
        for (EvolutionCandidate candidate : candidates) {
            if (candidate == null || !bundle.getId().equals(candidate.getBaseVersion())) {
                continue;
            }
            if (StringValueSupport.isBlank(candidate.getArtifactStreamId())
                    || StringValueSupport.isBlank(candidate.getBaseContentRevisionId())) {
                continue;
            }
            String previous = bindings.putIfAbsent(candidate.getArtifactStreamId(),
                    candidate.getBaseContentRevisionId());
            if (previous == null) {
                updated = true;
            }
        }
        if (updated) {
            bundle.setArtifactRevisionBindings(bindings);
        }
        return updated;
    }

    private ArtifactBundleRecord copyBundle(ArtifactBundleRecord baseBundle, String bundleId,
            EvolutionCandidate candidate) {
        if (baseBundle == null) {
            return ArtifactBundleRecord.builder()
                    .id(bundleId)
                    .golemId(candidate.getGolemId())
                    .status("snapshot")
                    .createdAt(Instant.now(clock))
                    .build();
        }
        return ArtifactBundleRecord.builder()
                .id(bundleId)
                .golemId(baseBundle.getGolemId())
                .sourceRunId(baseBundle.getSourceRunId())
                .sourceCandidateId(baseBundle.getSourceCandidateId())
                .status(baseBundle.getStatus())
                .createdAt(Instant.now(clock))
                .activatedAt(baseBundle.getActivatedAt())
                .skillVersions(baseBundle.getSkillVersions() != null ? new ArrayList<>(baseBundle.getSkillVersions())
                        : new ArrayList<>())
                .promptVersions(baseBundle.getPromptVersions() != null ? new ArrayList<>(baseBundle.getPromptVersions())
                        : new ArrayList<>())
                .policyVersions(baseBundle.getPolicyVersions() != null ? new ArrayList<>(baseBundle.getPolicyVersions())
                        : new ArrayList<>())
                .artifactRevisionBindings(baseBundle.getArtifactRevisionBindings() != null
                        ? new LinkedHashMap<>(baseBundle.getArtifactRevisionBindings())
                        : new LinkedHashMap<>())
                .artifactKeyBindings(baseBundle.getArtifactKeyBindings() != null
                        ? new LinkedHashMap<>(baseBundle.getArtifactKeyBindings())
                        : new LinkedHashMap<>())
                .artifactTypeBindings(baseBundle.getArtifactTypeBindings() != null
                        ? new LinkedHashMap<>(baseBundle.getArtifactTypeBindings())
                        : new LinkedHashMap<>())
                .artifactSubtypeBindings(baseBundle.getArtifactSubtypeBindings() != null
                        ? new LinkedHashMap<>(baseBundle.getArtifactSubtypeBindings())
                        : new LinkedHashMap<>())
                .tierBindings(baseBundle.getTierBindings() != null ? new LinkedHashMap<>(baseBundle.getTierBindings())
                        : new LinkedHashMap<>())
                .configSnapshot(
                        baseBundle.getConfigSnapshot() != null ? new LinkedHashMap<>(baseBundle.getConfigSnapshot())
                                : new LinkedHashMap<>())
                .build();
    }

    private List<ArtifactBundleRecord> loadBundles() {
        try {
            String json = storagePort.getText(SELF_EVOLVING_DIR, BUNDLES_FILE).join();
            if (StringValueSupport.isBlank(json)) {
                return new ArrayList<>();
            }
            List<ArtifactBundleRecord> bundles = objectMapper.readValue(json, BUNDLE_LIST_TYPE);
            return bundles != null ? new ArrayList<>(bundles) : new ArrayList<>();
        } catch (IOException | RuntimeException e) { // NOSONAR - storage fallback
            log.debug("[SelfEvolving] Failed to load artifact bundles: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    private void saveBundles(List<ArtifactBundleRecord> bundles) {
        try {
            String json = objectMapper.writeValueAsString(bundles);
            storagePort.putText(SELF_EVOLVING_DIR, BUNDLES_FILE, json).join();
            bundleCache.set(new ArrayList<>(bundles));
        } catch (Exception e) { // NOSONAR - storage failure becomes runtime error
            throw new IllegalStateException("Failed to persist artifact bundles", e);
        }
    }

    private List<String> resolveSkillVersions(AgentContext context) {
        List<String> skillVersions = new ArrayList<>();
        if (context.getActiveSkill() != null && !StringValueSupport.isBlank(context.getActiveSkill().getName())) {
            skillVersions.add(context.getActiveSkill().getName());
        }
        if (context.getActiveSkills() != null) {
            for (Skill skill : context.getActiveSkills()) {
                if (skill == null || StringValueSupport.isBlank(skill.getName())) {
                    continue;
                }
                if (!skillVersions.contains(skill.getName())) {
                    skillVersions.add(skill.getName());
                }
            }
        }
        return skillVersions;
    }

    private Map<String, String> resolveTierBindings(AgentContext context) {
        Map<String, String> tierBindings = new LinkedHashMap<>();
        if (!StringValueSupport.isBlank(context.getModelTier())) {
            tierBindings.put("active", context.getModelTier());
        }
        tierBindings.put("judge.primary", runtimeConfigService.getSelfEvolvingJudgePrimaryTier());
        tierBindings.put("judge.tiebreaker", runtimeConfigService.getSelfEvolvingJudgeTiebreakerTier());
        tierBindings.put("judge.evolution", runtimeConfigService.getSelfEvolvingJudgeEvolutionTier());
        return tierBindings;
    }

    private Map<String, Object> buildConfigSnapshot(AgentContext context) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("selfEvolvingEnabled", runtimeConfigService.isSelfEvolvingEnabled());
        snapshot.put("tracePayloadOverride", runtimeConfigService.isSelfEvolvingTracePayloadOverrideEnabled());
        snapshot.put("promotionMode", runtimeConfigService.getSelfEvolvingPromotionMode());
        snapshot.put("conversationKey", context.getAttribute(ContextAttributes.CONVERSATION_KEY));
        return snapshot;
    }

    private Map<String, String> resolveArtifactKeyBindings(AgentContext context) {
        Map<String, String> bindings = new LinkedHashMap<>();
        List<String> skillVersions = resolveSkillVersions(context);
        if (skillVersions.isEmpty()) {
            bindings.put("skill:default", "skill:default");
        } else {
            for (String skillVersion : skillVersions) {
                bindings.put("skill:" + skillVersion, "skill:" + skillVersion);
            }
        }
        bindings.put("prompt:section", "prompt:section");
        bindings.put("routing_policy:tier", "routing_policy:tier");
        bindings.put("tool_policy:usage", "tool_policy:usage");
        bindings.put("memory_policy:retrieval", "memory_policy:retrieval");
        bindings.put("context_policy:assembly", "context_policy:assembly");
        bindings.put("governance_policy:approval", "governance_policy:approval");
        return bindings;
    }

    private Map<String, String> resolveArtifactTypeBindings(AgentContext context) {
        Map<String, String> bindings = new LinkedHashMap<>();
        for (String artifactKey : resolveArtifactKeyBindings(context).keySet()) {
            bindings.put(artifactKey, artifactKey.contains(":") ? artifactKey.substring(0, artifactKey.indexOf(':'))
                    : artifactKey);
        }
        return bindings;
    }

    private Map<String, String> resolveArtifactSubtypeBindings(AgentContext context) {
        Map<String, String> bindings = new LinkedHashMap<>();
        for (String artifactKey : resolveArtifactKeyBindings(context).keySet()) {
            bindings.put(artifactKey, artifactKey.startsWith("skill:") ? "skill" : artifactKey);
        }
        return bindings;
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
