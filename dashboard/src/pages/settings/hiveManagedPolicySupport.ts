import type { HiveStatusResponse } from '../../api/hive';

export interface HiveManagedPolicyDetails {
  policyGroupId: string;
  targetVersion: number | null;
  appliedVersion: number | null;
  syncStatus: string | null;
  lastErrorDigest: string | null;
}

export function getHiveManagedPolicyDetails(status?: HiveStatusResponse | null): HiveManagedPolicyDetails | null {
  if (status?.policyGroupId == null || status.policyGroupId.trim().length === 0) {
    return null;
  }

  return {
    policyGroupId: status.policyGroupId,
    targetVersion: status.targetPolicyVersion,
    appliedVersion: status.appliedPolicyVersion,
    syncStatus: status.policySyncStatus,
    lastErrorDigest: status.lastPolicyErrorDigest,
  };
}

export function formatHivePolicyVersion(version: number | null | undefined): string {
  return version == null ? 'none' : `v${version}`;
}

export function resolveHiveManagedPolicyVariant(
  syncStatus: string | null | undefined,
): 'success' | 'warning' | 'danger' | 'secondary' | 'primary' {
  if (syncStatus === 'IN_SYNC') {
    return 'success';
  }
  if (syncStatus === 'SYNC_PENDING' || syncStatus === 'APPLYING') {
    return 'primary';
  }
  if (syncStatus === 'OUT_OF_SYNC') {
    return 'warning';
  }
  if (syncStatus === 'APPLY_FAILED') {
    return 'danger';
  }
  return 'secondary';
}
