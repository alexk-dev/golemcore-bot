package me.golemcore.bot.adapter.outbound.hive;

import java.util.Set;
import lombok.RequiredArgsConstructor;
import me.golemcore.bot.domain.model.hive.HiveCapabilitySnapshot;
import me.golemcore.bot.domain.model.hive.HivePolicyApplyResult;
import me.golemcore.bot.domain.model.hive.HivePolicyPackage;
import me.golemcore.bot.port.outbound.HiveMachinePort;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class HiveMachinePortAdapter implements HiveMachinePort {

    private final HiveApiClient hiveApiClient;

    @Override
    public AuthSession register(
            String serverUrl,
            String enrollmentToken,
            String displayName,
            String hostLabel,
            String runtimeVersion,
            String buildVersion,
            Set<String> supportedChannels,
            HiveCapabilitySnapshot capabilities) {
        try {
            return toAuthSession(hiveApiClient.register(
                    serverUrl,
                    enrollmentToken,
                    displayName,
                    hostLabel,
                    runtimeVersion,
                    buildVersion,
                    supportedChannels,
                    capabilities));
        } catch (HiveApiClient.HiveApiException exception) {
            throw translate(exception);
        }
    }

    @Override
    public AuthSession rotate(String serverUrl, String golemId, String refreshToken) {
        try {
            return toAuthSession(hiveApiClient.rotate(serverUrl, golemId, refreshToken));
        } catch (HiveApiClient.HiveApiException exception) {
            throw translate(exception);
        }
    }

    @Override
    public void heartbeat(
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
            String lastPolicyErrorDigest) {
        try {
            hiveApiClient.heartbeat(
                    serverUrl,
                    golemId,
                    accessToken,
                    status,
                    healthSummary,
                    lastErrorSummary,
                    uptimeSeconds,
                    capabilitySnapshotHash,
                    policyGroupId,
                    targetPolicyVersion,
                    appliedPolicyVersion,
                    syncStatus,
                    lastPolicyErrorDigest);
        } catch (HiveApiClient.HiveApiException exception) {
            throw translate(exception);
        }
    }

    @Override
    public HivePolicyPackage getPolicyPackage(String serverUrl, String golemId, String accessToken) {
        try {
            return hiveApiClient.getPolicyPackage(serverUrl, golemId, accessToken);
        } catch (HiveApiClient.HiveApiException exception) {
            throw translate(exception);
        }
    }

    @Override
    public HivePolicyApplyResult reportPolicyApplyResult(
            String serverUrl,
            String golemId,
            String accessToken,
            HivePolicyApplyResult applyResult) {
        try {
            return hiveApiClient.reportPolicyApplyResult(serverUrl, golemId, accessToken, applyResult);
        } catch (HiveApiClient.HiveApiException exception) {
            throw translate(exception);
        }
    }

    private AuthSession toAuthSession(HiveApiClient.GolemAuthResponse response) {
        return new AuthSession(
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

    private HiveMachineException translate(HiveApiClient.HiveApiException exception) {
        return new HiveMachineException(exception.getStatusCode(), exception.getMessage(), exception);
    }
}
