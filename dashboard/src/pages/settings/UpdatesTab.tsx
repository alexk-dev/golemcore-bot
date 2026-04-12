import { useCallback, type ReactElement } from 'react';
import { Alert, Button, Card, Spinner } from 'react-bootstrap';
import toast from 'react-hot-toast';
import type {
  SystemUpdateActionResponse,
  SystemUpdateConfigResponse,
  SystemUpdateStatusResponse,
} from '../../api/system';
import SettingsCardTitle from '../../components/common/SettingsCardTitle';
import { useUpdateAutoReload } from '../../hooks/useUpdateAutoReload';
import {
  useCheckSystemUpdate,
  useSystemUpdateConfig,
  useSystemUpdateStatus,
  useUpdateSystemConfig,
  useUpdateSystemNow,
} from '../../hooks/useSystem';
import { extractErrorMessage } from '../../utils/extractErrorMessage';
import {
  getPrimaryUpdateVersion,
  getUpdateActionLabel,
  getUpdateStateDescription,
  getUpdateWorkflowPresentation,
  hasPendingUpdate,
} from '../../utils/systemUpdateUi';
import { UpdateWorkflowPanel } from './UpdateWorkflowPanel';
import { UpdateSettingsCard } from './UpdateSettingsCard';
import {
  resolveExpectedUpdateVersion,
  useUpdateActionAvailability,
  useUpdateAutoReloadLifecycle,
  useUpdateConfigForm,
} from './UpdatesTabHooks';
import type { UpdateSettingsFormState } from './updateSettingsUtils';


interface UpdatesErrorCardProps {
  onRetry: () => void;
}

interface UpdatesBodyProps {
  status: SystemUpdateStatusResponse;
  config: SystemUpdateConfigResponse | null;
  configForm: UpdateSettingsFormState | null;
  canCheck: boolean;
  canUpdate: boolean;
  isBusy: boolean;
  isCheckPending: boolean;
  isUpdatePending: boolean;
  isConfigLoading: boolean;
  isConfigSaving: boolean;
  isAutoReloadArmed: boolean;
  autoReloadState: ReturnType<typeof useUpdateAutoReload>;
  onCheck: () => void;
  onUpdateNow: () => void;
  onConfigRetry: () => void;
  onConfigChange: (updater: (current: UpdateSettingsFormState) => UpdateSettingsFormState) => void;
  onConfigSave: () => void;
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

function UpdatesBody({
  status,
  config,
  configForm,
  canCheck,
  canUpdate,
  isBusy,
  isCheckPending,
  isUpdatePending,
  isConfigLoading,
  isConfigSaving,
  isAutoReloadArmed,
  autoReloadState,
  onCheck,
  onUpdateNow,
  onConfigRetry,
  onConfigChange,
  onConfigSave,
}: UpdatesBodyProps): ReactElement {
  const pendingVersion = getPrimaryUpdateVersion(status);
  const idleHint = getIdleHint(status);
  const workflow = getUpdateWorkflowPresentation(status);

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

      <UpdateSettingsCard
        status={status}
        config={config}
        form={configForm}
        isLoading={isConfigLoading}
        isSaving={isConfigSaving}
        onRetry={onConfigRetry}
        onChange={onConfigChange}
        onSave={onConfigSave}
      />
    </>
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
  const configQuery = useSystemUpdateConfig();
  const checkMutation = useCheckSystemUpdate();
  const updateNowMutation = useUpdateSystemNow();
  const updateConfigMutation = useUpdateSystemConfig();
  const status = statusQuery.data ?? null;
  const config = configQuery.data ?? null;
  const [configForm, setConfigForm, currentPayload] = useUpdateConfigForm(config);
  const refetchStatus = useCallback((): void => { void statusQuery.refetch(); }, [statusQuery]);
  const [isAutoReloadArmed, setIsAutoReloadArmed] = useUpdateAutoReloadLifecycle({
    status,
    isUpdatePending: updateNowMutation.isPending,
    refetchStatus,
  });

  const autoReloadState = useUpdateAutoReload({
    currentVersion: status?.current?.version ?? null,
    expectedVersion: resolveExpectedUpdateVersion(status),
    isEnabled: isAutoReloadArmed,
  });

  const { isBusy, canCheck, canUpdate } = useUpdateActionAvailability(
    status,
    checkMutation.isPending,
    updateNowMutation.isPending,
  );

  const handleConfigRetry = useCallback((): void => {
    void configQuery.refetch();
  }, [configQuery]);

  const handleConfigChange = useCallback((updater: (current: UpdateSettingsFormState) => UpdateSettingsFormState): void => {
    setConfigForm((current) => (current == null ? current : updater(current)));
  }, [setConfigForm]);


  if (statusQuery.isLoading) {
    return <UpdatesLoadingCard />;
  }
  if (statusQuery.isError || status == null) {
    return <UpdatesErrorCard onRetry={() => { void statusQuery.refetch(); }} />;
  }



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

  const handleConfigSave = (): void => {
    if (currentPayload == null) {
      toast.error('Update settings are incomplete.');
      return;
    }

    void (async () => {
      try {
        await updateConfigMutation.mutateAsync(currentPayload);
        await Promise.all([statusQuery.refetch(), configQuery.refetch()]);
        toast.success('Auto update settings saved.');
      } catch (error: unknown) {
        toast.error(`Save failed: ${extractErrorMessage(error)}`);
      }
    })();
  };

  return (
    <UpdatesBody
      status={status}
      config={config}
      configForm={configForm}
      canCheck={canCheck}
      canUpdate={canUpdate}
      isBusy={isBusy}
      isCheckPending={checkMutation.isPending}
      isUpdatePending={updateNowMutation.isPending}
      isConfigLoading={configQuery.isLoading}
      isConfigSaving={updateConfigMutation.isPending}
      isAutoReloadArmed={isAutoReloadArmed}
      autoReloadState={autoReloadState}
      onCheck={handleCheck}
      onUpdateNow={handleUpdateNow}
      onConfigRetry={handleConfigRetry}
      onConfigChange={handleConfigChange}
      onConfigSave={handleConfigSave}
    />
  );
}
