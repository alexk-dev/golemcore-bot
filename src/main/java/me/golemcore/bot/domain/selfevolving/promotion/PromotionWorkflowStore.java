package me.golemcore.bot.domain.selfevolving.promotion;

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
import me.golemcore.bot.domain.model.selfevolving.PromotionDecision;
import me.golemcore.bot.port.outbound.StoragePort;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import me.golemcore.bot.domain.service.StringValueSupport;

/**
 * Persistent storage and in-memory cache for self-evolving candidates and
 * promotion decisions.
 */
@Service
@Slf4j
public class PromotionWorkflowStore {

    private static final String SELF_EVOLVING_DIR = "self-evolving";
    private static final String CANDIDATES_FILE = "candidates.json";
    private static final String DECISIONS_FILE = "promotion-decisions.json";
    private static final TypeReference<List<EvolutionCandidate>> CANDIDATE_LIST_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<PromotionDecision>> DECISION_LIST_TYPE = new TypeReference<>() {
    };

    private final StoragePort storagePort;
    private final ObjectMapper objectMapper;
    private final AtomicReference<List<EvolutionCandidate>> candidateCache = new AtomicReference<>();
    private final AtomicReference<List<PromotionDecision>> decisionCache = new AtomicReference<>();

    public PromotionWorkflowStore(StoragePort storagePort) {
        this.storagePort = storagePort;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    public List<EvolutionCandidate> getCandidates() {
        List<EvolutionCandidate> cached = candidateCache.get();
        if (cached == null) {
            cached = loadCandidates();
            candidateCache.set(cached);
        }
        return new ArrayList<>(cached);
    }

    public List<PromotionDecision> getPromotionDecisions() {
        List<PromotionDecision> cached = decisionCache.get();
        if (cached == null) {
            cached = loadPromotionDecisions();
            decisionCache.set(cached);
        }
        return new ArrayList<>(cached);
    }

    public void saveCandidates(List<EvolutionCandidate> candidates) {
        try {
            String json = objectMapper.writeValueAsString(candidates);
            storagePort.putTextAtomic(SELF_EVOLVING_DIR, CANDIDATES_FILE, json, true).join();
            candidateCache.set(candidates != null ? new ArrayList<>(candidates) : new ArrayList<>());
        } catch (Exception e) { // NOSONAR - storage failure becomes runtime error
            throw new IllegalStateException("Failed to persist self-evolving candidates", e);
        }
    }

    public void savePromotionDecisions(List<PromotionDecision> decisions) {
        try {
            String json = objectMapper.writeValueAsString(decisions);
            storagePort.putTextAtomic(SELF_EVOLVING_DIR, DECISIONS_FILE, json, true).join();
            decisionCache.set(decisions != null ? new ArrayList<>(decisions) : new ArrayList<>());
        } catch (Exception e) { // NOSONAR - storage failure becomes runtime error
            throw new IllegalStateException("Failed to persist promotion decisions", e);
        }
    }

    private List<EvolutionCandidate> loadCandidates() {
        try {
            String json = storagePort.getText(SELF_EVOLVING_DIR, CANDIDATES_FILE).join();
            if (StringValueSupport.isBlank(json)) {
                return new ArrayList<>();
            }
            List<EvolutionCandidate> candidates = objectMapper.readValue(json, CANDIDATE_LIST_TYPE);
            return candidates != null ? new ArrayList<>(candidates) : new ArrayList<>();
        } catch (IOException | RuntimeException e) { // NOSONAR - storage fallback
            log.debug("[SelfEvolving] Failed to load candidates: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    private List<PromotionDecision> loadPromotionDecisions() {
        try {
            String json = storagePort.getText(SELF_EVOLVING_DIR, DECISIONS_FILE).join();
            if (StringValueSupport.isBlank(json)) {
                return new ArrayList<>();
            }
            List<PromotionDecision> decisions = objectMapper.readValue(json, DECISION_LIST_TYPE);
            return decisions != null ? new ArrayList<>(decisions) : new ArrayList<>();
        } catch (IOException | RuntimeException e) { // NOSONAR - storage fallback
            log.debug("[SelfEvolving] Failed to load promotion decisions: {}", e.getMessage());
            return new ArrayList<>();
        }
    }
}
