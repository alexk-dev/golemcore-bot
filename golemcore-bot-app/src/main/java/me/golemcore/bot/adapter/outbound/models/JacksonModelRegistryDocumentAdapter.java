package me.golemcore.bot.adapter.outbound.models;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import me.golemcore.bot.domain.model.catalog.ModelCatalogEntry;
import me.golemcore.bot.port.outbound.ModelRegistryDocumentPort;
import org.springframework.stereotype.Component;

@Component
public class JacksonModelRegistryDocumentAdapter implements ModelRegistryDocumentPort {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    @Override
    public ModelCatalogEntry parseCatalogEntry(String json) {
        try {
            return OBJECT_MAPPER.readValue(json, ModelCatalogEntry.class);
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Failed to parse model registry config", exception);
        }
    }
}
