package me.golemcore.bot.domain.model.catalog;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ModelReasoningProfile {

    @JsonProperty("default")
    private String defaultLevel;

    private Map<String, ModelReasoningLevel> levels = new LinkedHashMap<>();
}
