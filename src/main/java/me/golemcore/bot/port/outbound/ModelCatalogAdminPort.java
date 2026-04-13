package me.golemcore.bot.port.outbound;

import me.golemcore.bot.domain.model.hive.HivePolicyModelCatalog;

public interface ModelCatalogAdminPort {

    HivePolicyModelCatalog getCatalogSnapshot();

    void replaceCatalogSnapshot(HivePolicyModelCatalog catalogSnapshot);
}
