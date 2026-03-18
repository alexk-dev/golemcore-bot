package me.golemcore.bot.domain.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class HiveSessionState {

    @Builder.Default
    private int schemaVersion = 1;

    private String golemId;
    private String serverUrl;
    private String controlChannelUrl;
    private String issuer;
    private String audience;
    private String accessToken;
    private String refreshToken;
    private Instant accessTokenExpiresAt;
    private Instant refreshTokenExpiresAt;
    private Integer heartbeatIntervalSeconds;
    @Builder.Default
    private List<String> scopes = new ArrayList<>();
    private Instant registeredAt;
    private Instant lastConnectedAt;
    private Instant lastHeartbeatAt;
    private String lastError;
}
