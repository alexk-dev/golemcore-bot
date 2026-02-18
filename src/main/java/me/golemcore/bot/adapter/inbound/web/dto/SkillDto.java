package me.golemcore.bot.adapter.inbound.web.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillDto {
    private String name;
    private String description;
    private boolean available;
    private String content;
    private String modelTier;
    private boolean hasMcp;
    private Map<String, Object> requirements;
    private Map<String, String> resolvedVariables;
}
