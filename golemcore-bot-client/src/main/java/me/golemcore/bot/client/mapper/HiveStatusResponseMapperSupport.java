package me.golemcore.bot.client.mapper;

import me.golemcore.bot.client.dto.HiveStatusResponse;
import me.golemcore.bot.domain.model.hive.HiveStatusSnapshot;

public final class HiveStatusResponseMapperSupport {

    private HiveStatusResponseMapperSupport() {
    }

    public static HiveStatusResponse toResponse(HiveStatusSnapshot snapshot) {
        return new HiveStatusResponse(
                snapshot.state(),
                snapshot.enabled(),
                snapshot.managedByProperties(),
                snapshot.managedJoinCodeAvailable(),
                snapshot.autoConnect(),
                snapshot.serverUrl(),
                snapshot.displayName(),
                snapshot.hostLabel(),
                snapshot.dashboardBaseUrl(),
                snapshot.ssoEnabled(),
                snapshot.sessionPresent(),
                snapshot.golemId(),
                snapshot.controlChannelUrl(),
                snapshot.heartbeatIntervalSeconds(),
                snapshot.lastConnectedAt(),
                snapshot.lastHeartbeatAt(),
                snapshot.lastTokenRotatedAt(),
                snapshot.controlChannelState(),
                snapshot.controlChannelConnectedAt(),
                snapshot.controlChannelLastMessageAt(),
                snapshot.controlChannelLastError(),
                snapshot.lastReceivedCommandId(),
                snapshot.lastReceivedCommandAt(),
                snapshot.receivedCommandCount(),
                snapshot.bufferedCommandCount(),
                snapshot.pendingCommandCount(),
                snapshot.pendingEventBatchCount(),
                snapshot.pendingEventCount(),
                snapshot.outboxLastError(),
                snapshot.lastError(),
                snapshot.policyGroupId(),
                snapshot.targetPolicyVersion(),
                snapshot.appliedPolicyVersion(),
                snapshot.policySyncStatus(),
                snapshot.lastPolicyErrorDigest());
    }
}
