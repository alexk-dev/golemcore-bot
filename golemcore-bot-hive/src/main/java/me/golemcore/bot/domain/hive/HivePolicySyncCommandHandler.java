package me.golemcore.bot.domain.hive;

import me.golemcore.bot.domain.model.HiveControlCommandEnvelope;
import org.springframework.stereotype.Service;

@Service
public class HivePolicySyncCommandHandler {

    private final HiveManagedPolicyService hiveManagedPolicyService;

    public HivePolicySyncCommandHandler(HiveManagedPolicyService hiveManagedPolicyService) {
        this.hiveManagedPolicyService = hiveManagedPolicyService;
    }

    public void handle(HiveControlCommandEnvelope envelope) {
        if (envelope == null) {
            throw new IllegalArgumentException("Hive policy sync command is required");
        }
        if (envelope.getPolicyGroupId() == null || envelope.getPolicyGroupId().isBlank()) {
            throw new IllegalArgumentException("Hive policy sync policyGroupId is required");
        }
        if (envelope.getTargetVersion() == null) {
            throw new IllegalArgumentException("Hive policy sync targetVersion is required");
        }
        if (envelope.getChecksum() == null || envelope.getChecksum().isBlank()) {
            throw new IllegalArgumentException("Hive policy sync checksum is required");
        }
        hiveManagedPolicyService.markSyncRequested(
                envelope.getPolicyGroupId(),
                envelope.getTargetVersion(),
                envelope.getChecksum());
    }
}
