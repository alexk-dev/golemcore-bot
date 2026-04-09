package me.golemcore.bot.port.outbound;

import java.util.Set;
import me.golemcore.bot.domain.model.hive.HiveAuthSession;

public interface HiveGatewayPort {

    HiveAuthSession registerGolem(
            String serverUrl,
            String enrollmentToken,
            String displayName,
            String hostLabel,
            String runtimeVersion,
            String buildVersion,
            Set<String> supportedChannels);

    HiveAuthSession rotateSession(String serverUrl, String golemId, String refreshToken);

    void sendHeartbeat(
            String serverUrl,
            String golemId,
            String accessToken,
            String status,
            String healthSummary,
            String lastErrorSummary,
            Long uptimeSeconds);

    boolean isAuthorizationFailure(RuntimeException exception);
}
