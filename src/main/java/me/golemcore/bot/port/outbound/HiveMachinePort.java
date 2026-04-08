package me.golemcore.bot.port.outbound;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import me.golemcore.bot.domain.model.hive.HiveCapabilitySnapshot;
import me.golemcore.bot.domain.model.hive.HivePolicyApplyResult;
import me.golemcore.bot.domain.model.hive.HivePolicyPackage;

public interface HiveMachinePort {

    AuthSession register(
            String serverUrl,
            String enrollmentToken,
            String displayName,
            String hostLabel,
            String runtimeVersion,
            String buildVersion,
            Set<String> supportedChannels,
            HiveCapabilitySnapshot capabilities);

    AuthSession rotate(String serverUrl, String golemId, String refreshToken);

    void heartbeat(
            String serverUrl,
            String golemId,
            String accessToken,
            String status,
            String healthSummary,
            String lastErrorSummary,
            Long uptimeSeconds,
            String capabilitySnapshotHash,
            String policyGroupId,
            Integer targetPolicyVersion,
            Integer appliedPolicyVersion,
            String syncStatus,
            String lastPolicyErrorDigest);

    HivePolicyPackage getPolicyPackage(String serverUrl, String golemId, String accessToken);

    HivePolicyApplyResult reportPolicyApplyResult(
            String serverUrl,
            String golemId,
            String accessToken,
            HivePolicyApplyResult applyResult);

    record AuthSession(
            String golemId,
            String accessToken,
            String refreshToken,
            Instant accessTokenExpiresAt,
            Instant refreshTokenExpiresAt,
            String issuer,
            String audience,
            String controlChannelUrl,
            int heartbeatIntervalSeconds,
            List<String> scopes) {
    }

    final class HiveMachineException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        private final int statusCode;

        public HiveMachineException(int statusCode, String message, Throwable cause) {
            super(message, cause);
            this.statusCode = statusCode;
        }

        public int getStatusCode() {
            return statusCode;
        }
    }
}
