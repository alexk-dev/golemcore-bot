package me.golemcore.bot.port.outbound;

import me.golemcore.bot.domain.model.policy.ManagedModelCatalog;

public interface ModelCatalogAdminPort {

    ManagedModelCatalog getCatalogSnapshot();

    void replaceCatalogSnapshot(ManagedModelCatalog catalogSnapshot);
}
