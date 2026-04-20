package me.golemcore.bot.adapter.outbound.selfevolving;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.model.selfevolving.EvolutionCandidate;
import me.golemcore.bot.domain.model.selfevolving.PromotionDecision;
import me.golemcore.bot.port.outbound.StoragePort;
import me.golemcore.bot.port.outbound.selfevolving.PromotionWorkflowStatePort;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class JsonPromotionWorkflowStateAdapter implements PromotionWorkflowStatePort {

    private static final String SELF_EVOLVING_DIR = "self-evolving";
    private static final String CANDIDATES_FILE = "candidates.json";
    private static final String DECISIONS_FILE = "promotion-decisions.json";
    private static final TypeReference<List<EvolutionCandidate>> CANDIDATE_LIST_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<PromotionDecision>> DECISION_LIST_TYPE = new TypeReference<>() {
    };

    private final StoragePort storagePort;
    private final ObjectMapper objectMapper;

    public JsonPromotionWorkflowStateAdapter(StoragePort storagePort) {
        this.storagePort = storagePort;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    @Override
    public List<EvolutionCandidate> loadCandidates() {
        try {
            String json = storagePort.getText(SELF_EVOLVING_DIR, CANDIDATES_FILE).join();
            if (json == null || json.isBlank()) {
                return new ArrayList<>();
            }
            List<EvolutionCandidate> candidates = objectMapper.readValue(json, CANDIDATE_LIST_TYPE);
            return candidates != null ? new ArrayList<>(candidates) : new ArrayList<>();
        } catch (IOException | RuntimeException exception) {
            log.debug("[SelfEvolving] Failed to load candidates: {}", exception.getMessage());
            return new ArrayList<>();
        }
    }

    @Override
    public List<PromotionDecision> loadPromotionDecisions() {
        try {
            String json = storagePort.getText(SELF_EVOLVING_DIR, DECISIONS_FILE).join();
            if (json == null || json.isBlank()) {
                return new ArrayList<>();
            }
            List<PromotionDecision> decisions = objectMapper.readValue(json, DECISION_LIST_TYPE);
            return decisions != null ? new ArrayList<>(decisions) : new ArrayList<>();
        } catch (IOException | RuntimeException exception) {
            log.debug("[SelfEvolving] Failed to load promotion decisions: {}", exception.getMessage());
            return new ArrayList<>();
        }
    }

    @Override
    public void saveCandidates(List<EvolutionCandidate> candidates) {
        try {
            String json = objectMapper.writeValueAsString(candidates);
            storagePort.putTextAtomic(SELF_EVOLVING_DIR, CANDIDATES_FILE, json, true).join();
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to persist self-evolving candidates", exception);
        }
    }

    @Override
    public void savePromotionDecisions(List<PromotionDecision> decisions) {
        try {
            String json = objectMapper.writeValueAsString(decisions);
            storagePort.putTextAtomic(SELF_EVOLVING_DIR, DECISIONS_FILE, json, true).join();
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to persist promotion decisions", exception);
        }
    }
}
