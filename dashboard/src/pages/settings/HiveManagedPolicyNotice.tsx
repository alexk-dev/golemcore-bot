import type { ReactElement } from 'react';
import { Alert, Badge } from 'react-bootstrap';
import {
  formatHivePolicyVersion,
  resolveHiveManagedPolicyVariant,
  type HiveManagedPolicyDetails,
} from './hiveManagedPolicySupport';

interface HiveManagedPolicyNoticeProps {
  policy: HiveManagedPolicyDetails;
  sectionLabel: string;
  className?: string;
}

function resolveAlertVariant(syncStatus: string | null): 'info' | 'warning' | 'danger' {
  if (syncStatus === 'APPLY_FAILED') {
    return 'danger';
  }
  if (syncStatus === 'OUT_OF_SYNC' || syncStatus === 'SYNC_PENDING' || syncStatus === 'APPLYING') {
    return 'warning';
  }
  return 'info';
}

export function HiveManagedPolicyNotice({
  policy,
  sectionLabel,
  className,
}: HiveManagedPolicyNoticeProps): ReactElement {
  return (
    <Alert variant={resolveAlertVariant(policy.syncStatus)} className={className}>
      <div className="d-flex flex-wrap align-items-center gap-2 mb-2">
        <strong>{sectionLabel} managed by Hive</strong>
        <Badge bg="dark">{policy.policyGroupId}</Badge>
        <Badge bg={resolveHiveManagedPolicyVariant(policy.syncStatus)}>
          {policy.syncStatus ?? 'UNKNOWN'}
        </Badge>
      </div>

      <div className="small">
        Hive policy group controls this section. Applied {formatHivePolicyVersion(policy.appliedVersion)}.
        {' '}
        Target {formatHivePolicyVersion(policy.targetVersion)}.
      </div>

      {policy.lastErrorDigest ? (
        <div className="small mt-2 text-break">
          Last policy error: {policy.lastErrorDigest}
        </div>
      ) : null}
    </Alert>
  );
}
