package me.golemcore.bot.adapter.outbound.plan;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import lombok.RequiredArgsConstructor;
import me.golemcore.bot.domain.model.Plan;
import me.golemcore.bot.port.outbound.PlanStorePort;
import me.golemcore.bot.port.outbound.StoragePort;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class StoragePlanStoreAdapter implements PlanStorePort {

    private static final String AUTO_DIR = "auto";
    private static final String PLANS_FILE = "plans.json";
    private static final TypeReference<List<Plan>> PLAN_LIST_TYPE_REF = new TypeReference<>() {
    };

    private final StoragePort storagePort;
    private final ObjectMapper objectMapper;

    @Override
    public List<Plan> loadPlans() {
        try {
            String json = storagePort.getText(AUTO_DIR, PLANS_FILE).join();
            if (json == null || json.isBlank()) {
                return new ArrayList<>();
            }
            return new ArrayList<>(objectMapper.readValue(json, PLAN_LIST_TYPE_REF));
        } catch (IOException | RuntimeException exception) { // NOSONAR - fallback to empty state
            throw new IllegalStateException("Failed to load plans", unwrapCompletion(exception));
        }
    }

    @Override
    public void savePlans(List<Plan> plans) {
        try {
            String json = objectMapper.writeValueAsString(plans);
            storagePort.putText(AUTO_DIR, PLANS_FILE, json).join();
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to persist plans", unwrapCompletion(exception));
        }
    }

    private Throwable unwrapCompletion(Throwable throwable) {
        if (throwable instanceof CompletionException && throwable.getCause() != null) {
            return throwable.getCause();
        }
        return throwable;
    }
}
