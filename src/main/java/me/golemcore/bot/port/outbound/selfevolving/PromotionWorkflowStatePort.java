package me.golemcore.bot.port.outbound.selfevolving;

import java.util.List;
import me.golemcore.bot.domain.model.selfevolving.EvolutionCandidate;
import me.golemcore.bot.domain.model.selfevolving.PromotionDecision;

public interface PromotionWorkflowStatePort {

    List<EvolutionCandidate> loadCandidates();

    List<PromotionDecision> loadPromotionDecisions();

    void saveCandidates(List<EvolutionCandidate> candidates);

    void savePromotionDecisions(List<PromotionDecision> decisions);
}
