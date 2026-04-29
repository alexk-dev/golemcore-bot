package me.golemcore.bot.adapter.outbound.dashboard;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import me.golemcore.bot.domain.model.AdminCredentials;
import me.golemcore.bot.port.outbound.DashboardCredentialsPort;
import me.golemcore.bot.port.outbound.StoragePort;
import org.springframework.stereotype.Component;

@Component
public class StorageDashboardCredentialsAdapter implements DashboardCredentialsPort {

    private static final String ADMIN_DIR = "preferences";
    private static final String ADMIN_FILE = "admin.json";

    private final StoragePort storagePort;
    private final ObjectMapper objectMapper;

    public StorageDashboardCredentialsAdapter(
            StoragePort storagePort,
            ObjectMapper objectMapper) {
        this.storagePort = storagePort;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<AdminCredentials> load() {
        try {
            Boolean exists = storagePort.exists(ADMIN_DIR, ADMIN_FILE).join();
            if (!Boolean.TRUE.equals(exists)) {
                return Optional.empty();
            }

            String json = storagePort.getText(ADMIN_DIR, ADMIN_FILE).join();
            if (json == null || json.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(json, AdminCredentials.class));
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to load dashboard admin credentials", exception);
        }
    }

    @Override
    public void save(AdminCredentials credentials) {
        try {
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(credentials);
            storagePort.putText(ADMIN_DIR, ADMIN_FILE, json).join();
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to persist dashboard admin credentials", exception);
        }
    }
}
