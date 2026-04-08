package me.golemcore.bot.adapter.outbound.hive;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.model.hive.HivePolicyBindingState;
import me.golemcore.bot.port.outbound.HivePolicyStatePort;
import me.golemcore.bot.port.outbound.StoragePort;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class JsonHivePolicyStateAdapter implements HivePolicyStatePort {

    private static final String PREFERENCES_DIR = "preferences";
    private static final String POLICY_STATE_FILE = "hive-policy-state.json";

    private final StoragePort storagePort;
    private final ObjectMapper objectMapper;

    private final Object lock = new Object();
    private HivePolicyBindingState cachedState;
    private volatile boolean loaded;

    @Override
    public Optional<HivePolicyBindingState> load() {
        ensureLoaded();
        synchronized (lock) {
            return Optional.ofNullable(copy(cachedState));
        }
    }

    @Override
    public void save(HivePolicyBindingState state) {
        if (state == null) {
            throw new IllegalArgumentException("Hive policy binding state is required");
        }
        HivePolicyBindingState snapshot = copy(state);
        synchronized (lock) {
            try {
                String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(snapshot);
                storagePort.putTextAtomic(PREFERENCES_DIR, POLICY_STATE_FILE, json, true).join();
                cachedState = snapshot;
                loaded = true;
            } catch (Exception exception) {
                throw new IllegalStateException("Failed to persist Hive policy binding state", exception);
            }
        }
    }

    @Override
    @SuppressWarnings("PMD.NullAssignment")
    public void clear() {
        synchronized (lock) {
            try {
                storagePort.deleteObject(PREFERENCES_DIR, POLICY_STATE_FILE).join();
            } catch (RuntimeException exception) {
                log.warn("[Hive] Failed to delete persisted policy binding state: {}", exception.getMessage());
            }
            cachedState = null;
            loaded = true;
        }
    }

    private void ensureLoaded() {
        if (loaded) {
            return;
        }
        synchronized (lock) {
            if (loaded) {
                return;
            }
            cachedState = loadState();
            loaded = true;
        }
    }

    private HivePolicyBindingState loadState() {
        try {
            String json = storagePort.getText(PREFERENCES_DIR, POLICY_STATE_FILE).join();
            if (json == null || json.isBlank()) {
                return null;
            }
            return objectMapper.readValue(json, HivePolicyBindingState.class);
        } catch (IOException | RuntimeException exception) { // NOSONAR - startup should degrade gracefully
            log.warn("[Hive] Failed to load persisted policy binding state: {}", exception.getMessage());
            return null;
        }
    }

    private HivePolicyBindingState copy(HivePolicyBindingState source) {
        if (source == null) {
            return null;
        }
        return HivePolicyBindingState.builder()
                .schemaVersion(source.getSchemaVersion())
                .policyGroupId(source.getPolicyGroupId())
                .targetVersion(source.getTargetVersion())
                .appliedVersion(source.getAppliedVersion())
                .checksum(source.getChecksum())
                .syncStatus(source.getSyncStatus())
                .lastSyncRequestedAt(source.getLastSyncRequestedAt())
                .lastAppliedAt(source.getLastAppliedAt())
                .lastErrorDigest(source.getLastErrorDigest())
                .lastErrorAt(source.getLastErrorAt())
                .build();
    }
}
