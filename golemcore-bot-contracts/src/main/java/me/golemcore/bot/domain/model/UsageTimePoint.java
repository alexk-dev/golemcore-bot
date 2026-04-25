package me.golemcore.bot.domain.model;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/**
 * Single data point in a usage timeline series.
 */
@Data
@Builder
public class UsageTimePoint {

    private Instant timestamp;
    private long inputTokens;
    private long outputTokens;
    private long requests;
    private String model;
}
