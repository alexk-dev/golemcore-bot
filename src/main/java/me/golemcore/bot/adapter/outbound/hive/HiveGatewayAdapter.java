package me.golemcore.bot.adapter.outbound.hive;

import java.util.Set;
import lombok.RequiredArgsConstructor;
import me.golemcore.bot.domain.model.hive.HiveAuthSession;
import me.golemcore.bot.port.outbound.HiveGatewayPort;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class HiveGatewayAdapter implements HiveGatewayPort {

    private final HiveApiClient hiveApiClient;

    @Override
    public HiveAuthSession registerGolem(
            String serverUrl,
            String enrollmentToken,
            String displayName,
            String hostLabel,
            String runtimeVersion,
            String buildVersion,
            Set<String> supportedChannels) {
        return toDomain(hiveApiClient.register(
                serverUrl,
                enrollmentToken,
                displayName,
                hostLabel,
                runtimeVersion,
                buildVersion,
                supportedChannels));
    }

    @Override
    public HiveAuthSession rotateSession(String serverUrl, String golemId, String refreshToken) {
        return toDomain(hiveApiClient.rotate(serverUrl, golemId, refreshToken));
    }

    @Override
    public void sendHeartbeat(
            String serverUrl,
            String golemId,
            String accessToken,
            String status,
            String healthSummary,
            String lastErrorSummary,
            Long uptimeSeconds) {
        hiveApiClient.heartbeat(
                serverUrl,
                golemId,
                accessToken,
                status,
                healthSummary,
                lastErrorSummary,
                uptimeSeconds);
    }

    @Override
    public boolean isAuthorizationFailure(RuntimeException exception) {
        return exception instanceof HiveApiClient.HiveApiException hiveApiException
                && (hiveApiException.getStatusCode() == 401 || hiveApiException.getStatusCode() == 403);
    }

    private HiveAuthSession toDomain(HiveApiClient.GolemAuthResponse response) {
        return new HiveAuthSession(
                response.golemId(),
                response.accessToken(),
                response.refreshToken(),
                response.accessTokenExpiresAt(),
                response.refreshTokenExpiresAt(),
                response.issuer(),
                response.audience(),
                response.controlChannelUrl(),
                response.heartbeatIntervalSeconds(),
                response.scopes());
    }
}
