import type { ReactElement } from 'react';
import { Alert, Button, Card } from '../../components/ui/tailwind-components';

import type { SystemUpdateStatusResponse } from '../../api/system';
import ConfirmModal from '../../components/common/ConfirmModal';
import SettingsCardTitle from '../../components/common/SettingsCardTitle';
import {
  getPrimaryUpdateVersion,
  getUpdateActionLabel,
  getUpdateStateDescription,
  getUpdateWorkflowPresentation,
  hasPendingUpdate,
} from '../../utils/systemUpdateUi';
import { UpdateWorkflowPanel } from './UpdateWorkflowPanel';

interface AutoReloadStateSnapshot {
  hasSeenDowntime: boolean;
  lastProbeError: string | null;
}

interface UpdateOverviewCardProps {
  status: SystemUpdateStatusResponse;
  canCheck: boolean;
  canUpdate: boolean;
  canForceInstall: boolean;
  isBusy: boolean;
  isCheckPending: boolean;
  isUpdatePending: boolean;
  isForceInstallPending: boolean;
  isAutoReloadArmed: boolean;
  autoReloadState: AutoReloadStateSnapshot;
  isForceInstallConfirmOpen: boolean;
  forceInstallMessage: string;
  onCheck: () => void;
  onUpdateNow: () => void;
  onOpenForceInstallConfirm: () => void;
  onConfirmForceInstall: () => void;
  onCancelForceInstall: () => void;
}

interface UpdateWorkflowActionsProps {
  status: SystemUpdateStatusResponse;
  canCheck: boolean;
  canUpdate: boolean;
  canForceInstall: boolean;
  isBusy: boolean;
  isCheckPending: boolean;
  isUpdatePending: boolean;
  isForceInstallPending: boolean;
  onCheck: () => void;
  onUpdateNow: () => void;
  onOpenForceInstallConfirm: () => void;
}

interface UpdateStatusMessagesProps {
  status: SystemUpdateStatusResponse;
  canForceInstall: boolean;
}

function getIdleHint(status: SystemUpdateStatusResponse): string | null {
  if (!status.enabled) {
    return 'Updates are disabled by backend configuration.';
  }
  if (status.state === 'IDLE' && !hasPendingUpdate(status)) {
    return 'No update is queued. Run a check to look for a newer compatible release.';
  }
  if (status.state === 'FAILED' && hasPendingUpdate(status)) {
    return 'A target version is still available. Review the error and retry when ready.';
  }
  return null;
}

function buildUpdatesFootnote(status: SystemUpdateStatusResponse): string {
  const pendingVersion = getPrimaryUpdateVersion(status);
  if (pendingVersion == null) {
    return 'Applying an update restarts the backend and refreshes this page automatically.';
  }
  return `Target version: ${pendingVersion}. Applying the update restarts the backend and refreshes this page automatically.`;
}

function UpdateWorkflowActions({
  status,
  canCheck,
  canUpdate,
  canForceInstall,
  isBusy,
  isCheckPending,
  isUpdatePending,
  isForceInstallPending,
  onCheck,
  onUpdateNow,
  onOpenForceInstallConfirm,
}: UpdateWorkflowActionsProps): ReactElement {
  return (
    <div className="updates-actions">
      <Button type="button" size="sm" variant="secondary" onClick={onCheck} disabled={!canCheck}>
        {isCheckPending ? 'Checking...' : 'Check for updates'}
      </Button>
      <Button type="button" size="sm" variant="primary" onClick={onUpdateNow} disabled={!canUpdate}>
        {isUpdatePending || isBusy ? 'Updating...' : getUpdateActionLabel(status)}
      </Button>
      {canForceInstall && (
        <Button type="button" size="sm" variant="warning" onClick={onOpenForceInstallConfirm} disabled={isForceInstallPending}>
          {isForceInstallPending ? 'Force installing...' : 'Force install now'}
        </Button>
      )}
    </div>
  );
}

function UpdateStatusMessages({ status, canForceInstall }: UpdateStatusMessagesProps): ReactElement {
  const idleHint = getIdleHint(status);
  const workflow = getUpdateWorkflowPresentation(status);

  return (
    <>
      <div className="small text-body-secondary updates-footnote">
        {buildUpdatesFootnote(status)}
      </div>

      {canForceInstall && (
        <Alert variant="warning" className="mb-3 small">
          The update package is already staged locally. If runtime activity looks stuck, you can force the install and restart now.
        </Alert>
      )}

      {idleHint != null && (
        <Alert variant="secondary" className="mb-3 small">
          {idleHint}
        </Alert>
      )}

      {status.lastError != null && status.lastError.trim().length > 0 && (
        <Alert variant="danger" className="mb-0 small">
          {status.lastError}
        </Alert>
      )}

      {status.lastError == null && idleHint == null && status.state === 'FAILED' && (
        <Alert variant="danger" className="mb-0 small">
          {getUpdateStateDescription(status.state)}
        </Alert>
      )}

      {status.lastError == null && idleHint == null && status.state === 'IDLE' && !hasPendingUpdate(status) && (
        <Alert variant="secondary" className="mb-0 small">
          {workflow.description}
        </Alert>
      )}
    </>
  );
}

export function UpdateOverviewCard({
  status,
  canCheck,
  canUpdate,
  canForceInstall,
  isBusy,
  isCheckPending,
  isUpdatePending,
  isForceInstallPending,
  isAutoReloadArmed,
  autoReloadState,
  isForceInstallConfirmOpen,
  forceInstallMessage,
  onCheck,
  onUpdateNow,
  onOpenForceInstallConfirm,
  onConfirmForceInstall,
  onCancelForceInstall,
}: UpdateOverviewCardProps): ReactElement {
  return (
    <>
      <Card className="settings-card updates-card">
        <Card.Body>
          <SettingsCardTitle title="Updates" tip="Track every update stage and restart automatically when the new runtime is ready" />

          <UpdateWorkflowPanel
            status={status}
            isAutoReloadActive={isAutoReloadArmed}
            hasSeenDowntime={autoReloadState.hasSeenDowntime}
            lastProbeError={autoReloadState.lastProbeError}
          />

          <UpdateWorkflowActions
            status={status}
            canCheck={canCheck}
            canUpdate={canUpdate}
            canForceInstall={canForceInstall}
            isBusy={isBusy}
            isCheckPending={isCheckPending}
            isUpdatePending={isUpdatePending}
            isForceInstallPending={isForceInstallPending}
            onCheck={onCheck}
            onUpdateNow={onUpdateNow}
            onOpenForceInstallConfirm={onOpenForceInstallConfirm}
          />

          <UpdateStatusMessages status={status} canForceInstall={canForceInstall} />
        </Card.Body>
      </Card>

      <ConfirmModal
        show={isForceInstallConfirmOpen}
        title="Force install staged update?"
        message={forceInstallMessage}
        confirmLabel="Force install"
        confirmVariant="warning"
        isProcessing={isForceInstallPending}
        onConfirm={onConfirmForceInstall}
        onCancel={onCancelForceInstall}
      />
    </>
  );
}
