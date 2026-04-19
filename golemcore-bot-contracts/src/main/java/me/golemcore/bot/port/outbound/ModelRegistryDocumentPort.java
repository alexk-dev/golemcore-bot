package me.golemcore.bot.port.outbound;

import me.golemcore.bot.domain.model.catalog.ModelCatalogEntry;

public interface ModelRegistryDocumentPort {

    ModelCatalogEntry parseCatalogEntry(String json);
}
