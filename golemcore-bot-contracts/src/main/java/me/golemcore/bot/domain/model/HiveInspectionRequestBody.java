package me.golemcore.bot.domain.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class HiveInspectionRequestBody {

    private String operation;
    private String sessionId;
    private String snapshotId;
    private String channel;
    private Integer limit;
    private String beforeMessageId;
    private Integer keepLast;
}
