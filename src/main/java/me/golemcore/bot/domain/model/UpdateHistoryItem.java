package me.golemcore.bot.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UpdateHistoryItem {
    private String operation;
    private String version;
    private Instant timestamp;
    private String result;
    private String message;
}
