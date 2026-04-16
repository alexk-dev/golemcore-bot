import { useCallback, useState, type ReactElement } from 'react';
import { Button, Card, Spinner } from '../../components/ui/tailwind-components';
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
  useForceInstallStagedUpdate,
  useSystemUpdateConfig,
  useSystemUpdateStatus,
  useUpdateSystemConfig,
  useUpdateSystemNow,
} from '../../hooks/useSystem';
import { extractErrorMessage } from '../../utils/extractErrorMessage';
import { getPrimaryUpdateVersion, getUpdateBlockedReasonLabel } from '../../utils/systemUpdateUi';
import { UpdateOverviewCard } from './UpdateOverviewCard';
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
  canForceInstall: boolean;
  isBusy: boolean;
  isCheckPending: boolean;
  isUpdatePending: boolean;
  isForceInstallPending: boolean;
  isConfigLoading: boolean;
  isConfigSaving: boolean;
  isAutoReloadArmed: boolean;
  autoReloadState: ReturnType<typeof useUpdateAutoReload>;
  isForceInstallConfirmOpen: boolean;
  forceInstallMessage: string;
  onCheck: () => void;
  onUpdateNow: () => void;
  onOpenForceInstallConfirm: () => void;
  onConfirmForceInstall: () => void;
  onCancelForceInstall: () => void;
  onConfigRetry: () => void;
  onConfigChange: (updater: (current: UpdateSettingsFormState) => UpdateSettingsFormState) => void;
  onConfigSave: () => void;
}

function buildForceInstallMessage(status: SystemUpdateStatusResponse): string {
  const version = getPrimaryUpdateVersion(status) ?? 'the staged update';
  const blockedReason = getUpdateBlockedReasonLabel(status.blockedReason) ?? 'Runtime activity is still reported as busy.';
  return `The update package for ${version} is already staged locally, but the normal install action is blocked because ${blockedReason}. Force install will restart the backend immediately and may interrupt the current work.`;
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
        <div className="alert alert-warning mb-3">
          Unable to load update status from backend.
        </div>
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
  canForceInstall,
  isBusy,
  isCheckPending,
  isUpdatePending,
  isForceInstallPending,
  isConfigLoading,
  isConfigSaving,
  isAutoReloadArmed,
  autoReloadState,
  isForceInstallConfirmOpen,
  forceInstallMessage,
  onCheck,
  onUpdateNow,
  onOpenForceInstallConfirm,
  onConfirmForceInstall,
  onCancelForceInstall,
  onConfigRetry,
  onConfigChange,
  onConfigSave,
}: UpdatesBodyProps): ReactElement {
  return (
    <>
      <UpdateOverviewCard
        status={status}
        canCheck={canCheck}
        canUpdate={canUpdate}
        canForceInstall={canForceInstall}
        isBusy={isBusy}
        isCheckPending={isCheckPending}
        isUpdatePending={isUpdatePending}
        isForceInstallPending={isForceInstallPending}
        isAutoReloadArmed={isAutoReloadArmed}
        autoReloadState={autoReloadState}
        isForceInstallConfirmOpen={isForceInstallConfirmOpen}
        forceInstallMessage={forceInstallMessage}
        onCheck={onCheck}
        onUpdateNow={onUpdateNow}
        onOpenForceInstallConfirm={onOpenForceInstallConfirm}
        onConfirmForceInstall={onConfirmForceInstall}
        onCancelForceInstall={onCancelForceInstall}
      />

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
  const forceInstallMutation = useForceInstallStagedUpdate();
  const updateConfigMutation = useUpdateSystemConfig();
  const [isForceInstallConfirmOpen, setIsForceInstallConfirmOpen] = useState<boolean>(false);
  const status = statusQuery.data ?? null;
  const config = configQuery.data ?? null;
  const [configForm, setConfigForm, currentPayload] = useUpdateConfigForm(config);
  const refetchStatus = useCallback((): void => { void statusQuery.refetch(); }, [statusQuery]);
  const [isAutoReloadArmed, setIsAutoReloadArmed] = useUpdateAutoReloadLifecycle({
    status,
    isUpdatePending: updateNowMutation.isPending,
    isForceInstallPending: forceInstallMutation.isPending,
    refetchStatus,
  });

  const autoReloadState = useUpdateAutoReload({
    currentVersion: status?.current?.version ?? null,
    expectedVersion: resolveExpectedUpdateVersion(status),
    isEnabled: isAutoReloadArmed,
  });

  const { isBusy, canCheck, canUpdate, canForceInstall } = useUpdateActionAvailability(
    status,
    checkMutation.isPending,
    updateNowMutation.isPending,
    forceInstallMutation.isPending,
  );

  const handleConfigRetry = useCallback((): void => {
    void configQuery.refetch();
  }, [configQuery]);

  const handleConfigChange = useCallback((updater: (current: UpdateSettingsFormState) => UpdateSettingsFormState): void => {
    setConfigForm((current) => (current == null ? current : updater(current)));
  }, [setConfigForm]);

  const handleOpenForceInstallConfirm = useCallback((): void => {
    setIsForceInstallConfirmOpen(true);
  }, []);

  const handleCancelForceInstall = useCallback((): void => {
    if (forceInstallMutation.isPending) {
      return;
    }
    setIsForceInstallConfirmOpen(false);
  }, [forceInstallMutation.isPending]);

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

  const handleConfirmForceInstall = (): void => {
    setIsAutoReloadArmed(true);
    void runUpdateAction(async () => {
      const result = await forceInstallMutation.mutateAsync();
      setIsForceInstallConfirmOpen(false);
      await statusQuery.refetch();
      return result;
    }, 'Force install failed', () => {
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
      canForceInstall={canForceInstall}
      isBusy={isBusy}
      isCheckPending={checkMutation.isPending}
      isUpdatePending={updateNowMutation.isPending}
      isForceInstallPending={forceInstallMutation.isPending}
      isConfigLoading={configQuery.isLoading}
      isConfigSaving={updateConfigMutation.isPending}
      isAutoReloadArmed={isAutoReloadArmed}
      autoReloadState={autoReloadState}
      isForceInstallConfirmOpen={isForceInstallConfirmOpen}
      forceInstallMessage={buildForceInstallMessage(status)}
      onCheck={handleCheck}
      onUpdateNow={handleUpdateNow}
      onOpenForceInstallConfirm={handleOpenForceInstallConfirm}
      onConfirmForceInstall={handleConfirmForceInstall}
      onCancelForceInstall={handleCancelForceInstall}
      onConfigRetry={handleConfigRetry}
      onConfigChange={handleConfigChange}
      onConfigSave={handleConfigSave}
    />
  );
}
