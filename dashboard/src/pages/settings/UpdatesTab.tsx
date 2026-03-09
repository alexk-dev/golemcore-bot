import { useEffect, useState, type ReactElement } from 'react';
import { Alert, Button, Card, Spinner } from 'react-bootstrap';
import toast from 'react-hot-toast';
import type { SystemUpdateActionResponse, SystemUpdateStatusResponse } from '../../api/system';
import SettingsCardTitle from '../../components/common/SettingsCardTitle';
import { useUpdateAutoReload } from '../../hooks/useUpdateAutoReload';
import { useCheckSystemUpdate, useSystemUpdateStatus, useUpdateSystemNow } from '../../hooks/useSystem';
import { extractErrorMessage } from '../../utils/extractErrorMessage';
import {
  UPDATE_BUSY_STATES,
  getPrimaryUpdateVersion,
  getUpdateActionLabel,
  getUpdateStateDescription,
  getUpdateWorkflowPresentation,
  hasPendingUpdate,
} from '../../utils/systemUpdateUi';
import { UpdateWorkflowPanel } from './UpdateWorkflowPanel';

const LIVE_STATUS_POLL_MS = 1500;

interface UpdatesErrorCardProps {
  onRetry: () => void;
}

interface UpdatesBodyProps {
  status: SystemUpdateStatusResponse;
  canCheck: boolean;
  canUpdate: boolean;
  isBusy: boolean;
  isCheckPending: boolean;
  isUpdatePending: boolean;
  isAutoReloadArmed: boolean;
  autoReloadState: ReturnType<typeof useUpdateAutoReload>;
  onCheck: () => void;
  onUpdateNow: () => void;
}

function UpdatesLoadingCard(): ReactElement {
  return (
    <Card className="settings-card updates-card">
      <Card.Body className="d-flex align-items-center gap-2">
        <Spinner size="sm" animation="border" />
        <span className="small text-body-secondary">Loading update status...</span>
      </Card.Body>
    </Card>
  );
}

function UpdatesErrorCard({ onRetry }: UpdatesErrorCardProps): ReactElement {
  return (
    <Card className="settings-card updates-card">
      <Card.Body>
        <SettingsCardTitle title="Updates" />
        <Alert variant="warning" className="mb-3">
          Unable to load update status from backend.
        </Alert>
        <Button type="button" size="sm" variant="secondary" onClick={onRetry}>
          Retry
        </Button>
      </Card.Body>
    </Card>
  );
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

function UpdatesBody({
  status,
  canCheck,
  canUpdate,
  isBusy,
  isCheckPending,
  isUpdatePending,
  isAutoReloadArmed,
  autoReloadState,
  onCheck,
  onUpdateNow,
}: UpdatesBodyProps): ReactElement {
  const pendingVersion = getPrimaryUpdateVersion(status);
  const idleHint = getIdleHint(status);
  const workflow = getUpdateWorkflowPresentation(status);

  return (
    <Card className="settings-card updates-card">
      <Card.Body>
        <SettingsCardTitle title="Updates" tip="Track every update stage and restart automatically when the new runtime is ready" />

        <UpdateWorkflowPanel
          status={status}
          isAutoReloadActive={isAutoReloadArmed}
          hasSeenDowntime={autoReloadState.hasSeenDowntime}
          lastProbeError={autoReloadState.lastProbeError}
        />

        <div className="updates-actions">
          <Button type="button" size="sm" variant="secondary" onClick={onCheck} disabled={!canCheck}>
            {isCheckPending ? 'Checking...' : 'Check for updates'}
          </Button>
          <Button type="button" size="sm" variant="primary" onClick={onUpdateNow} disabled={!canUpdate}>
            {isUpdatePending || isBusy ? 'Updating...' : getUpdateActionLabel(status)}
          </Button>
        </div>

        <div className="small text-body-secondary updates-footnote">
          {pendingVersion == null
            ? 'Applying an update restarts the backend and refreshes this page automatically.'
            : `Target version: ${pendingVersion}. Applying the update restarts the backend and refreshes this page automatically.`}
        </div>

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
      </Card.Body>
    </Card>
  );
}

async function runUpdateAction(
  action: () => Promise<SystemUpdateActionResponse>,
  errorPrefix: string,
  onError?: () => void,
): Promise<void> {
  try {
    const result = await action();
    toast.success(result.message);
  } catch (error: unknown) {
    onError?.();
    toast.error(`${errorPrefix}: ${extractErrorMessage(error)}`);
  }
}

export function UpdatesTab(): ReactElement {
  const statusQuery = useSystemUpdateStatus();
  const checkMutation = useCheckSystemUpdate();
  const updateNowMutation = useUpdateSystemNow();
  const status = statusQuery.data ?? null;
  const [isAutoReloadArmed, setIsAutoReloadArmed] = useState<boolean>(false);

  const autoReloadState = useUpdateAutoReload({
    currentVersion: status?.current?.version ?? null,
    expectedVersion: status == null ? null : getPrimaryUpdateVersion(status),
    isEnabled: isAutoReloadArmed,
  });

  useEffect(() => {
    // Poll update status aggressively while a check/update flow is active so the progress UI stays responsive.
    if (status == null) {
      return undefined;
    }

    const shouldLivePoll = isAutoReloadArmed || updateNowMutation.isPending || UPDATE_BUSY_STATES.has(status.state);
    if (!shouldLivePoll) {
      return undefined;
    }

    const intervalId = window.setInterval(() => {
      void statusQuery.refetch();
    }, LIVE_STATUS_POLL_MS);

    return () => {
      window.clearInterval(intervalId);
    };
  }, [isAutoReloadArmed, status, statusQuery, updateNowMutation.isPending]);

  useEffect(() => {
    // Stop waiting for auto-reload once the update flow ends without an in-flight restart to observe.
    if (!isAutoReloadArmed || status == null) {
      return;
    }
    if (status.state === 'FAILED') {
      setIsAutoReloadArmed(false);
      return;
    }
    if (status.state === 'IDLE' && !hasPendingUpdate(status)) {
      setIsAutoReloadArmed(false);
    }
  }, [isAutoReloadArmed, status]);

  if (statusQuery.isLoading) {
    return <UpdatesLoadingCard />;
  }
  if (statusQuery.isError || status == null) {
    return <UpdatesErrorCard onRetry={() => { void statusQuery.refetch(); }} />;
  }

  const isBusy = checkMutation.isPending || updateNowMutation.isPending || UPDATE_BUSY_STATES.has(status.state);
  const canCheck = status.enabled && !isBusy;
  const canUpdate = status.enabled && !isBusy && hasPendingUpdate(status);

  const handleCheck = (): void => {
    void runUpdateAction(async () => {
      const result = await checkMutation.mutateAsync();
      await statusQuery.refetch();
      return result;
    }, 'Check failed');
  };

  const handleUpdateNow = (): void => {
    setIsAutoReloadArmed(true);
    void runUpdateAction(async () => {
      const result = await updateNowMutation.mutateAsync();
      await statusQuery.refetch();
      return result;
    }, 'Update failed', () => {
      setIsAutoReloadArmed(false);
    });
  };

  return (
    <UpdatesBody
      status={status}
      canCheck={canCheck}
      canUpdate={canUpdate}
      isBusy={isBusy}
      isCheckPending={checkMutation.isPending}
      isUpdatePending={updateNowMutation.isPending}
      isAutoReloadArmed={isAutoReloadArmed}
      autoReloadState={autoReloadState}
      onCheck={handleCheck}
      onUpdateNow={handleUpdateNow}
    />
  );
}
