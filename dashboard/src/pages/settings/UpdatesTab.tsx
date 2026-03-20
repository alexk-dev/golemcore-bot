import { useEffect, useMemo, useState, type ReactElement } from 'react';
import { Alert, Button, Card, Form, Spinner } from 'react-bootstrap';
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
  UPDATE_BUSY_STATES,
  formatUpdateTimestamp,
  getPrimaryUpdateVersion,
  getUpdateActionLabel,
  getUpdateStateDescription,
  getUpdateWorkflowPresentation,
  hasPendingUpdate,
} from '../../utils/systemUpdateUi';
import { UpdateWorkflowPanel } from './UpdateWorkflowPanel';

const LIVE_STATUS_POLL_MS = 1500;
const DEFAULT_LOCAL_TIME = '00:00';
const TIME_INPUT_PATTERN = /^([01]\d|2[0-3]):([0-5]\d)$/;

interface UpdatesErrorCardProps {
  onRetry: () => void;
}

interface UpdateSettingsFormState {
  autoEnabled: boolean;
  checkIntervalMinutes: string;
  maintenanceWindowEnabled: boolean;
  maintenanceWindowStartLocal: string;
  maintenanceWindowEndLocal: string;
}

interface UpdateSettingsCardProps {
  status: SystemUpdateStatusResponse;
  config: SystemUpdateConfigResponse | null;
  form: UpdateSettingsFormState | null;
  isLoading: boolean;
  isSaving: boolean;
  onRetry: () => void;
  onChange: (updater: (current: UpdateSettingsFormState) => UpdateSettingsFormState) => void;
  onSave: () => void;
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

function padTime(value: number): string {
  return value.toString().padStart(2, '0');
}

function isValidTimeInput(value: string): boolean {
  return TIME_INPUT_PATTERN.test(value.trim());
}

function utcToLocalTimeInput(value: string | null | undefined): string {
  if (!value || !isValidTimeInput(value)) {
    return DEFAULT_LOCAL_TIME;
  }
  const [hours, minutes] = value.split(':').map((part) => Number(part));
  const date = new Date();
  date.setUTCHours(hours, minutes, 0, 0);
  return `${padTime(date.getHours())}:${padTime(date.getMinutes())}`;
}

function localTimeToUtc(value: string): string {
  if (!isValidTimeInput(value)) {
    return DEFAULT_LOCAL_TIME;
  }
  const [hours, minutes] = value.split(':').map((part) => Number(part));
  const date = new Date();
  date.setHours(hours, minutes, 0, 0);
  return `${padTime(date.getUTCHours())}:${padTime(date.getUTCMinutes())}`;
}

function configToForm(config: SystemUpdateConfigResponse): UpdateSettingsFormState {
  return {
    autoEnabled: config.autoEnabled,
    checkIntervalMinutes: String(config.checkIntervalMinutes),
    maintenanceWindowEnabled: config.maintenanceWindowEnabled,
    maintenanceWindowStartLocal: utcToLocalTimeInput(config.maintenanceWindowStartUtc),
    maintenanceWindowEndLocal: utcToLocalTimeInput(config.maintenanceWindowEndUtc),
  };
}

function normalizeIntervalValue(value: string): number | null {
  const parsed = Number.parseInt(value.trim(), 10);
  if (!Number.isFinite(parsed) || parsed < 1) {
    return null;
  }
  return parsed;
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

function UpdateSettingsCard({
  status,
  config,
  form,
  isLoading,
  isSaving,
  onRetry,
  onChange,
  onSave,
}: UpdateSettingsCardProps): ReactElement {
  const localTimezone = Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC';

  if (isLoading || form == null) {
    return (
      <Card className="settings-card updates-card mt-3">
        <Card.Body className="d-flex align-items-center gap-2">
          <Spinner size="sm" animation="border" />
          <span className="small text-body-secondary">Loading auto update settings...</span>
        </Card.Body>
      </Card>
    );
  }

  if (config == null) {
    return (
      <Card className="settings-card updates-card mt-3">
        <Card.Body>
          <SettingsCardTitle title="Auto Update Settings" />
          <Alert variant="warning" className="mb-3">
            Unable to load update settings from backend.
          </Alert>
          <Button type="button" size="sm" variant="secondary" onClick={onRetry}>
            Retry
          </Button>
        </Card.Body>
      </Card>
    );
  }

  const intervalValid = normalizeIntervalValue(form.checkIntervalMinutes) != null;
  const windowTimesValid = isValidTimeInput(form.maintenanceWindowStartLocal)
    && isValidTimeInput(form.maintenanceWindowEndLocal);
  const canSave = status.enabled
    && !isSaving
    && intervalValid
    && (!form.maintenanceWindowEnabled || windowTimesValid);

  const previewStartUtc = localTimeToUtc(form.maintenanceWindowStartLocal);
  const previewEndUtc = localTimeToUtc(form.maintenanceWindowEndLocal);
  const isDirty =
    form.autoEnabled !== config.autoEnabled
    || form.checkIntervalMinutes !== String(config.checkIntervalMinutes)
    || form.maintenanceWindowEnabled !== config.maintenanceWindowEnabled
    || localTimeToUtc(form.maintenanceWindowStartLocal) !== config.maintenanceWindowStartUtc
    || localTimeToUtc(form.maintenanceWindowEndLocal) !== config.maintenanceWindowEndUtc;

  const saveSummary = form.maintenanceWindowEnabled
    ? `${previewStartUtc}-${previewEndUtc} ${status.serverTimezone ?? 'UTC'}`
    : `Any time (${status.serverTimezone ?? 'UTC'})`;

  return (
    <Card className="settings-card updates-card mt-3">
      <Card.Body>
        <SettingsCardTitle
          title="Auto Update Settings"
          tip="Edit auto update policy in your local browser time. The backend stores the maintenance window in UTC."
        />

        <Form.Group className="mb-3">
          <Form.Check
            type="switch"
            label="Enable automatic updates"
            checked={form.autoEnabled}
            disabled={!status.enabled || isSaving}
            onChange={(event) => {
              const checked = event.target.checked;
              onChange((current) => ({
                ...current,
                autoEnabled: checked,
              }));
            }}
          />
        </Form.Group>

        <Form.Group className="mb-3">
          <Form.Label>Check interval (minutes)</Form.Label>
          <Form.Control
            size="sm"
            value={form.checkIntervalMinutes}
            disabled={!status.enabled || isSaving}
            isInvalid={!intervalValid}
            onChange={(event) => {
              const value = event.target.value;
              onChange((current) => ({
                ...current,
                checkIntervalMinutes: value,
              }));
            }}
          />
          <Form.Text className={!intervalValid ? 'text-danger' : 'text-body-secondary'}>
            Default is 60 minutes. Auto checks do not bypass maintenance window or runtime activity blocking.
          </Form.Text>
        </Form.Group>

        <Form.Group className="mb-3">
          <Form.Check
            type="switch"
            label="Use maintenance window"
            checked={form.maintenanceWindowEnabled}
            disabled={!status.enabled || isSaving}
            onChange={(event) => {
              const checked = event.target.checked;
              onChange((current) => ({
                ...current,
                maintenanceWindowEnabled: checked,
              }));
            }}
          />
          <Form.Text className="text-body-secondary">
            Disabled means updates may apply at any time once no work is running.
          </Form.Text>
        </Form.Group>

        <div className="row g-3 mb-3">
          <div className="col-md-6">
            <Form.Group>
              <Form.Label>Window start ({localTimezone})</Form.Label>
              <Form.Control
                size="sm"
                value={form.maintenanceWindowStartLocal}
                disabled={!status.enabled || isSaving || !form.maintenanceWindowEnabled}
                isInvalid={form.maintenanceWindowEnabled && !isValidTimeInput(form.maintenanceWindowStartLocal)}
                onChange={(event) => {
                  const value = event.target.value;
                  onChange((current) => ({
                    ...current,
                    maintenanceWindowStartLocal: value,
                  }));
                }}
                placeholder="HH:mm"
              />
            </Form.Group>
          </div>
          <div className="col-md-6">
            <Form.Group>
              <Form.Label>Window end ({localTimezone})</Form.Label>
              <Form.Control
                size="sm"
                value={form.maintenanceWindowEndLocal}
                disabled={!status.enabled || isSaving || !form.maintenanceWindowEnabled}
                isInvalid={form.maintenanceWindowEnabled && !isValidTimeInput(form.maintenanceWindowEndLocal)}
                onChange={(event) => {
                  const value = event.target.value;
                  onChange((current) => ({
                    ...current,
                    maintenanceWindowEndLocal: value,
                  }));
                }}
                placeholder="HH:mm"
              />
            </Form.Group>
          </div>
        </div>

        <Alert variant="secondary" className="mb-3 small">
          <div>Your timezone: {localTimezone}</div>
          <div>Saved on server as {saveSummary}</div>
          {status.state === 'WAITING_FOR_WINDOW' && status.nextEligibleAt != null && (
            <div>Next eligible window: {formatUpdateTimestamp(status.nextEligibleAt)}</div>
          )}
          {status.state === 'WAITING_FOR_IDLE' && status.blockedReason != null && (
            <div>Current blocker: {status.blockedReason}</div>
          )}
        </Alert>

        <div className="d-flex gap-2">
          <Button type="button" size="sm" variant="primary" disabled={!canSave || !isDirty} onClick={onSave}>
            {isSaving ? 'Saving...' : 'Save settings'}
          </Button>
          {status.enabled === false && (
            <span className="small text-body-secondary align-self-center">
              Backend update feature is disabled, so settings are read-only.
            </span>
          )}
        </div>
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
  const [configForm, setConfigForm] = useState<UpdateSettingsFormState | null>(null);
  const [isAutoReloadArmed, setIsAutoReloadArmed] = useState<boolean>(false);

  const autoReloadState = useUpdateAutoReload({
    currentVersion: status?.current?.version ?? null,
    expectedVersion: status == null ? null : getPrimaryUpdateVersion(status),
    isEnabled: isAutoReloadArmed,
  });

  useEffect(() => {
    if (config != null) {
      setConfigForm(configToForm(config));
    }
  }, [config]);

  useEffect(() => {
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

  const currentPayload = useMemo<SystemUpdateConfigResponse | null>(() => {
    if (configForm == null) {
      return null;
    }
    const interval = normalizeIntervalValue(configForm.checkIntervalMinutes);
    if (interval == null) {
      return null;
    }
    if (configForm.maintenanceWindowEnabled
        && (!isValidTimeInput(configForm.maintenanceWindowStartLocal)
          || !isValidTimeInput(configForm.maintenanceWindowEndLocal))) {
      return null;
    }
    return {
      autoEnabled: configForm.autoEnabled,
      checkIntervalMinutes: interval,
      maintenanceWindowEnabled: configForm.maintenanceWindowEnabled,
      maintenanceWindowStartUtc: localTimeToUtc(configForm.maintenanceWindowStartLocal),
      maintenanceWindowEndUtc: localTimeToUtc(configForm.maintenanceWindowEndLocal),
    };
  }, [configForm]);

  if (statusQuery.isLoading) {
    return <UpdatesLoadingCard />;
  }
  if (statusQuery.isError || status == null) {
    return <UpdatesErrorCard onRetry={() => { void statusQuery.refetch(); }} />;
  }

  const isWorkflowBusy = checkMutation.isPending || updateNowMutation.isPending || UPDATE_BUSY_STATES.has(status.state);
  const isBusy = isWorkflowBusy || Boolean(status.busy);
  const canCheck = status.enabled && !isWorkflowBusy;
  const canUpdate = status.enabled && !isWorkflowBusy && !Boolean(status.busy) && hasPendingUpdate(status);

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
      onConfigRetry={() => { void configQuery.refetch(); }}
      onConfigChange={(updater) => {
        setConfigForm((current) => (current == null ? current : updater(current)));
      }}
      onConfigSave={handleConfigSave}
    />
  );
}
