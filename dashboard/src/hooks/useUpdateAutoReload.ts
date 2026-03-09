import { useEffect, useRef, useState } from 'react';
import { getSystemHealth } from '../api/system';
import { extractErrorMessage } from '../utils/extractErrorMessage';

export interface UseUpdateAutoReloadOptions {
  currentVersion: string | null;
  expectedVersion: string | null;
  isEnabled: boolean;
}

export interface UpdateAutoReloadState {
  hasSeenDowntime: boolean;
  lastProbeError: string | null;
}

const AUTO_RELOAD_POLL_MS = 1500;

export function useUpdateAutoReload({
  currentVersion,
  expectedVersion,
  isEnabled,
}: UseUpdateAutoReloadOptions): UpdateAutoReloadState {
  const [hasSeenDowntime, setHasSeenDowntime] = useState<boolean>(false);
  const [lastProbeError, setLastProbeError] = useState<string | null>(null);
  const baselineVersionRef = useRef<string | null>(null);
  const downtimeSeenRef = useRef<boolean>(false);
  const reloadStartedRef = useRef<boolean>(false);

  useEffect(() => {
    // Reset baseline and transient probe state when auto-reload tracking starts or stops between update attempts.
    if (isEnabled) {
      if (baselineVersionRef.current == null) {
        baselineVersionRef.current = currentVersion;
      }
      return;
    }

    baselineVersionRef.current = null;
    downtimeSeenRef.current = false;
    reloadStartedRef.current = false;
    setHasSeenDowntime(false);
    setLastProbeError(null);
  }, [currentVersion, isEnabled]);

  useEffect(() => {
    // Watch backend reachability after an update starts so the SPA reloads as soon as the restarted runtime is back.
    if (!isEnabled) {
      return undefined;
    }

    let isCancelled = false;

    const probe = async (): Promise<void> => {
      try {
        const health = await getSystemHealth();
        if (isCancelled || reloadStartedRef.current) {
          return;
        }

        const baselineVersion = baselineVersionRef.current;
        const versionChanged = baselineVersion != null && health.version !== baselineVersion;
        const matchesExpectedVersion = expectedVersion != null && health.version === expectedVersion;
        if (downtimeSeenRef.current || versionChanged || matchesExpectedVersion) {
          reloadStartedRef.current = true;
          window.location.reload();
          return;
        }

        setLastProbeError(null);
      } catch (error: unknown) {
        if (isCancelled || reloadStartedRef.current) {
          return;
        }

        downtimeSeenRef.current = true;
        setHasSeenDowntime(true);
        setLastProbeError(extractErrorMessage(error));
      }
    };

    void probe();
    const intervalId = window.setInterval(() => {
      void probe();
    }, AUTO_RELOAD_POLL_MS);

    return () => {
      isCancelled = true;
      window.clearInterval(intervalId);
    };
  }, [expectedVersion, isEnabled]);

  return {
    hasSeenDowntime,
    lastProbeError,
  };
}
