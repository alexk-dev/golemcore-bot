import type { ReactElement } from 'react';
import { Alert, Badge, Button, Card, Spinner } from 'react-bootstrap';
import toast from 'react-hot-toast';
import type { SystemUpdateActionResponse, SystemUpdateStatusResponse } from '../../api/system';
import SettingsCardTitle from '../../components/common/SettingsCardTitle';
import { useCheckSystemUpdate, useSystemUpdateStatus, useUpdateSystemNow } from '../../hooks/useSystem';
import { extractErrorMessage } from '../../utils/extractErrorMessage';
import { UPDATE_BUSY_STATES, formatUpdateTimestamp, getUpdateStateLabel, getUpdateStateVariant } from '../../utils/systemUpdateUi';

interface UpdatesErrorCardProps {
  onRetry: () => void;
}

interface UpdateAvailabilityAlertProps {
  availableVersion: string | null;
  canUpdate: boolean;
  isUpdatePending: boolean;
  onUpdateNow: () => void;
}

interface UpdatesBodyProps {
  status: SystemUpdateStatusResponse;
  canCheck: boolean;
  canUpdate: boolean;
  isCheckPending: boolean;
  isUpdatePending: boolean;
  onCheck: () => void;
  onUpdateNow: () => void;
}

function formatVersionLabel(version: string | null): string {
  if (version == null || version.trim().length === 0) {
    return 'N/A';
  }
  return version;
}

async function runUpdateAction(action: () => Promise<SystemUpdateActionResponse>, errorPrefix: string): Promise<void> {
  try {
    const result = await action();
    toast.success(result.message);
  } catch (error: unknown) {
    toast.error(`${errorPrefix}: ${extractErrorMessage(error)}`);
  }
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

function UpdateAvailabilityAlert({
  availableVersion,
  canUpdate,
  isUpdatePending,
  onUpdateNow,
}: UpdateAvailabilityAlertProps): ReactElement {
  if (availableVersion == null) {
    return (
      <Alert variant="secondary" className="mb-3 small">
        No update available.
      </Alert>
    );
  }

  return (
    <Alert variant="warning" className="mb-3">
      <div className="fw-medium mb-1">Update available: {availableVersion}</div>
      <div className="small mb-2">Applying update will restart the service.</div>
      <Button type="button" size="sm" variant="primary" onClick={onUpdateNow} disabled={!canUpdate}>
        {isUpdatePending ? 'Updating...' : `Update to ${availableVersion}`}
      </Button>
    </Alert>
  );
}

function UpdatesBody({
  status,
  canCheck,
  canUpdate,
  isCheckPending,
  isUpdatePending,
  onCheck,
  onUpdateNow,
}: UpdatesBodyProps): ReactElement {
  const state = status.state;
  const currentVersion = status.current?.version ?? null;
  const availableVersion = status.available?.version ?? null;
  const hasLastError = status.lastError != null && status.lastError.trim().length > 0;

  return (
    <Card className="settings-card updates-card">
      <Card.Body>
        <SettingsCardTitle title="Updates" tip="Check for new release and update in one click" />

        <div className="d-flex align-items-center gap-2 mb-2">
          <span className="small text-body-secondary">State</span>
          <Badge bg={getUpdateStateVariant(state)}>{getUpdateStateLabel(state)}</Badge>
        </div>

        <div className="small text-body-secondary mb-1">
          Current version: <span className="text-body">{formatVersionLabel(currentVersion)}</span>
        </div>
        <div className="small text-body-secondary mb-3">
          Last check: <span className="text-body">{formatUpdateTimestamp(status.lastCheckAt)}</span>
        </div>

        <div className="d-flex flex-wrap gap-2 mb-3">
          <Button type="button" size="sm" variant="outline-primary" onClick={onCheck} disabled={!canCheck}>
            {isCheckPending ? 'Checking...' : 'Check for updates'}
          </Button>
        </div>

        <UpdateAvailabilityAlert
          availableVersion={availableVersion}
          canUpdate={canUpdate}
          isUpdatePending={isUpdatePending}
          onUpdateNow={onUpdateNow}
        />

        {!status.enabled && (
          <Alert variant="secondary" className="mb-0 small">
            Updates are disabled by backend configuration.
          </Alert>
        )}

        {hasLastError && (
          <Alert variant="danger" className="mb-0 small">
            {status.lastError}
          </Alert>
        )}
      </Card.Body>
    </Card>
  );
}

export function UpdatesTab(): ReactElement {
  const statusQuery = useSystemUpdateStatus();
  const checkMutation = useCheckSystemUpdate();
  const updateNowMutation = useUpdateSystemNow();
  const status = statusQuery.data ?? null;

  if (statusQuery.isLoading) {
    return <UpdatesLoadingCard />;
  }
  if (statusQuery.isError || status == null) {
    return <UpdatesErrorCard onRetry={() => { void statusQuery.refetch(); }} />;
  }

  const isBusy = checkMutation.isPending || updateNowMutation.isPending || UPDATE_BUSY_STATES.has(status.state);
  const canCheck = status.enabled && !isBusy;
  const canUpdate = status.enabled && !isBusy && status.available?.version != null;

  const handleCheck = (): void => {
    void runUpdateAction(() => checkMutation.mutateAsync(), 'Check failed');
  };

  const handleUpdateNow = (): void => {
    void runUpdateAction(() => updateNowMutation.mutateAsync(), 'Update failed');
  };

  return (
    <UpdatesBody
      status={status}
      canCheck={canCheck}
      canUpdate={canUpdate}
      isCheckPending={checkMutation.isPending}
      isUpdatePending={updateNowMutation.isPending}
      onCheck={handleCheck}
      onUpdateNow={handleUpdateNow}
    />
  );
}
