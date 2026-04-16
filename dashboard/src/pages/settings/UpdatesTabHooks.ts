import { useEffect, useMemo, useState } from 'react';

import type { SystemUpdateConfigResponse, SystemUpdateStatusResponse } from '../../api/system';
import { UPDATE_BUSY_STATES, canForceInstallStagedUpdate, getPrimaryUpdateVersion, hasPendingUpdate } from '../../utils/systemUpdateUi';
import { configToForm, isValidTimeInput, localTimeToUtc, normalizeIntervalValue, type UpdateSettingsFormState } from './updateSettingsUtils';

const LIVE_STATUS_POLL_MS = 1500;

interface AutoReloadLifecycleArgs {
  status: SystemUpdateStatusResponse | null;
  isUpdatePending: boolean;
  isForceInstallPending: boolean;
  refetchStatus: () => void;
}

export function useUpdateConfigForm(config: SystemUpdateConfigResponse | null): [
  UpdateSettingsFormState | null,
  React.Dispatch<React.SetStateAction<UpdateSettingsFormState | null>>,
  SystemUpdateConfigResponse | null,
] {
  const [configForm, setConfigForm] = useState<UpdateSettingsFormState | null>(null);
  useEffect(() => {
    if (config != null) {
      setConfigForm(configToForm(config));
    }
  }, [config]);

  const payload = useMemo<SystemUpdateConfigResponse | null>(() => {
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

  return [configForm, setConfigForm, payload];
}

export function useUpdateAutoReloadLifecycle({
  status,
  isUpdatePending,
  isForceInstallPending,
  refetchStatus,
}: AutoReloadLifecycleArgs): [boolean, React.Dispatch<React.SetStateAction<boolean>>] {
  const [isAutoReloadArmed, setIsAutoReloadArmed] = useState<boolean>(false);

  useEffect(() => {
    if (status == null) {
      return undefined;
    }
    const shouldLivePoll = isAutoReloadArmed || isUpdatePending || isForceInstallPending || UPDATE_BUSY_STATES.has(status.state);
    if (!shouldLivePoll) {
      return undefined;
    }
    const intervalId = window.setInterval(refetchStatus, LIVE_STATUS_POLL_MS);
    return () => {
      window.clearInterval(intervalId);
    };
  }, [isAutoReloadArmed, isForceInstallPending, isUpdatePending, refetchStatus, status]);

  useEffect(() => {
    if (!isAutoReloadArmed || status == null) {
      return;
    }
    if (status.state === 'FAILED' || (status.state === 'IDLE' && !hasPendingUpdate(status))) {
      setIsAutoReloadArmed(false);
    }
  }, [isAutoReloadArmed, status]);

  return [isAutoReloadArmed, setIsAutoReloadArmed];
}

export function resolveExpectedUpdateVersion(status: SystemUpdateStatusResponse | null): string | null {
  return status == null ? null : getPrimaryUpdateVersion(status);
}

export function useUpdateActionAvailability(
  status: SystemUpdateStatusResponse | null,
  isCheckPending: boolean,
  isUpdatePending: boolean,
  isForceInstallPending: boolean,
): { isBusy: boolean; canCheck: boolean; canUpdate: boolean; canForceInstall: boolean } {
  return useMemo(() => {
    if (status == null) {
      return { isBusy: false, canCheck: false, canUpdate: false, canForceInstall: false };
    }
    const isWorkflowBusy = isCheckPending || isUpdatePending || isForceInstallPending || UPDATE_BUSY_STATES.has(status.state);
    return {
      isBusy: isWorkflowBusy || Boolean(status.busy),
      canCheck: status.enabled && !isWorkflowBusy,
      canUpdate: status.enabled && !isWorkflowBusy && !status.busy && hasPendingUpdate(status),
      canForceInstall: !isWorkflowBusy && canForceInstallStagedUpdate(status),
    };
  }, [isCheckPending, isForceInstallPending, isUpdatePending, status]);
}
