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

import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.model.selfevolving.EvolutionCandidate;
import me.golemcore.bot.domain.model.selfevolving.PromotionDecision;
import me.golemcore.bot.port.outbound.selfevolving.PromotionWorkflowStatePort;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Persistent storage and in-memory cache for self-evolving candidates and
 * promotion decisions.
 */
@Service
@Slf4j
public class PromotionWorkflowStore {

    private final PromotionWorkflowStatePort promotionWorkflowStatePort;
    private final AtomicReference<List<EvolutionCandidate>> candidateCache = new AtomicReference<>();
    private final AtomicReference<List<PromotionDecision>> decisionCache = new AtomicReference<>();

    public PromotionWorkflowStore(PromotionWorkflowStatePort promotionWorkflowStatePort) {
        this.promotionWorkflowStatePort = promotionWorkflowStatePort;
    }

    public List<EvolutionCandidate> getCandidates() {
        List<EvolutionCandidate> cached = candidateCache.get();
        if (cached == null) {
            cached = promotionWorkflowStatePort.loadCandidates();
            candidateCache.set(cached);
        }
        return new ArrayList<>(cached);
    }

    public List<PromotionDecision> getPromotionDecisions() {
        List<PromotionDecision> cached = decisionCache.get();
        if (cached == null) {
            cached = promotionWorkflowStatePort.loadPromotionDecisions();
            decisionCache.set(cached);
        }
        return new ArrayList<>(cached);
    }

    public void saveCandidates(List<EvolutionCandidate> candidates) {
        promotionWorkflowStatePort.saveCandidates(candidates);
        candidateCache.set(candidates != null ? new ArrayList<>(candidates) : new ArrayList<>());
    }

    public void savePromotionDecisions(List<PromotionDecision> decisions) {
        promotionWorkflowStatePort.savePromotionDecisions(decisions);
        decisionCache.set(decisions != null ? new ArrayList<>(decisions) : new ArrayList<>());
    }
}
