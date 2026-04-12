package me.golemcore.bot.adapter.outbound.storage;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import me.golemcore.bot.domain.model.DelayedSessionAction;
import me.golemcore.bot.port.outbound.DelayedActionRegistryPort;
import me.golemcore.bot.port.outbound.StoragePort;
import org.springframework.stereotype.Component;

@Component
public class StorageDelayedActionRegistryAdapter implements DelayedActionRegistryPort {

    private static final String AUTOMATION_DIR = "automation";
    private static final String ACTIONS_FILE = "delayed-actions.json";
    private static final int REGISTRY_VERSION = 1;

    private final StoragePort storagePort;
    private final ObjectMapper objectMapper;

    public StorageDelayedActionRegistryAdapter(StoragePort storagePort) {
        this.storagePort = storagePort;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    @Override
    public List<DelayedSessionAction> loadActions() {
        try {
            String json = storagePort.getText(AUTOMATION_DIR, ACTIONS_FILE).join();
            if (json == null || json.isBlank()) {
                return new ArrayList<>();
            }
            Registry registry = objectMapper.readValue(json, Registry.class);
            return registry.getActions() != null ? new ArrayList<>(registry.getActions()) : new ArrayList<>();
        } catch (IOException | RuntimeException exception) {
            return new ArrayList<>();
        }
    }

    @Override
    public void saveActions(List<DelayedSessionAction> actions) {
        try {
            Registry registry = new Registry(REGISTRY_VERSION, null,
                    actions != null ? new ArrayList<>(actions) : new ArrayList<>());
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(registry);
            storagePort.putTextAtomic(AUTOMATION_DIR, ACTIONS_FILE, json, true).join();
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to persist delayed actions", exception);
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class Registry {
        private int version = REGISTRY_VERSION;
        private String updatedAt;
        private List<DelayedSessionAction> actions = new ArrayList<>();
    }
}
