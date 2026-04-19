package me.golemcore.bot.port.outbound;

import java.util.Optional;
import me.golemcore.bot.domain.model.AdminCredentials;

/**
 * Persists and retrieves dashboard admin credentials.
 */
public interface DashboardCredentialsPort {

    Optional<AdminCredentials> load();

    void save(AdminCredentials credentials);
}
