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
import me.golemcore.bot.domain.model.Skill;
import me.golemcore.bot.domain.model.selfevolving.ArtifactBundleRecord;
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

    public ArtifactBundleService(StoragePort storagePort, RuntimeConfigService runtimeConfigService) {
        this(storagePort, runtimeConfigService, Clock.systemUTC());
    }

    ArtifactBundleService(StoragePort storagePort, RuntimeConfigService runtimeConfigService, Clock clock) {
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

    private ArtifactBundleRecord buildSnapshot(String bundleId, AgentContext context) {
        return ArtifactBundleRecord.builder()
                .id(bundleId)
                .golemId(resolveGolemId(context))
                .status("SNAPSHOT")
                .createdAt(Instant.now(clock))
                .skillVersions(resolveSkillVersions(context))
                .tierBindings(resolveTierBindings(context))
                .configSnapshot(buildConfigSnapshot(context))
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
