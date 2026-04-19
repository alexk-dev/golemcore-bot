package me.golemcore.bot.domain.selfevolving.promotion;

import java.util.ArrayList;
import java.util.List;
import me.golemcore.bot.domain.model.selfevolving.EvolutionCandidate;
import me.golemcore.bot.domain.model.selfevolving.PromotionDecision;
import me.golemcore.bot.port.outbound.selfevolving.PromotionWorkflowStatePort;

public class InMemoryPromotionWorkflowStatePort implements PromotionWorkflowStatePort {

    private final List<EvolutionCandidate> candidates = new ArrayList<>();
    private final List<PromotionDecision> decisions = new ArrayList<>();

    @Override
    public List<EvolutionCandidate> loadCandidates() {
        return new ArrayList<>(candidates);
    }

    @Override
    public List<PromotionDecision> loadPromotionDecisions() {
        return new ArrayList<>(decisions);
    }

    @Override
    public void saveCandidates(List<EvolutionCandidate> updatedCandidates) {
        candidates.clear();
        if (updatedCandidates != null) {
            candidates.addAll(updatedCandidates);
        }
    }

    @Override
    public void savePromotionDecisions(List<PromotionDecision> updatedDecisions) {
        decisions.clear();
        if (updatedDecisions != null) {
            decisions.addAll(updatedDecisions);
        }
    }
}
