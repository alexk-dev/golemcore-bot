package me.golemcore.bot.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Scheduler report delivery configuration.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScheduleReportConfig {

    private String channelType;
    private String chatId;
    private String webhookUrl;
    private String webhookBearerToken;
}
