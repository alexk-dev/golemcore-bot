package me.golemcore.bot.domain.model.catalog;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ModelCatalogEntry {

    private String provider = "";
    private String displayName;
    private boolean supportsVision = true;
    private boolean supportsTemperature = true;
    private int maxInputTokens = 128000;
    private ModelReasoningProfile reasoning;

    public ModelCatalogEntry withProvider(String resolvedProvider) {
        return new ModelCatalogEntry(resolvedProvider, displayName, supportsVision, supportsTemperature,
                maxInputTokens, reasoning);
    }
}
