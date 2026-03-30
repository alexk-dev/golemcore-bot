package me.golemcore.bot.domain.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class HiveControlCommandEnvelope {

    @Builder.Default
    private int schemaVersion = 1;

    private String eventType;
    private String commandId;
    private String requestId;
    private String threadId;
    private String cardId;
    private String golemId;
    private String runId;
    private String body;
    private HiveInspectionRequestBody inspection;
    private Instant createdAt;
}
