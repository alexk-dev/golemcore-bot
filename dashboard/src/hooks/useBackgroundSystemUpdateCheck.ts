import { useEffect, useRef, type MutableRefObject } from 'react';
import type { SystemUpdateStatusResponse } from '../api/system';
import {
  AUTO_UPDATE_CHECK_INTERVAL_MS,
  type BackgroundUpdateCheckStatus,
  shouldCheckSystemUpdateInBackground,
} from '../utils/systemUpdateUi';
import { useCheckSystemUpdate } from './useSystem';

interface UpdateCheckSnapshot {
  status: BackgroundUpdateCheckStatus | null;
  isCheckPending: boolean;
}

type UpdateCheckMutate = ReturnType<typeof useCheckSystemUpdate>['mutate'];

function attemptBackgroundUpdateCheck(
  status: BackgroundUpdateCheckStatus | null,
  isCheckPending: boolean,
  lastAttemptAtRef: MutableRefObject<number | null>,
  mutate: UpdateCheckMutate,
): void {
  const now = Date.now();
  if (!shouldCheckSystemUpdateInBackground(status, lastAttemptAtRef.current, now, isCheckPending)) {
    return;
  }

  lastAttemptAtRef.current = now;
  mutate(undefined, {
    onError: () => {
      // Background checks stay silent; the Updates screen owns explicit failure messaging.
    },
  });
}

function buildStatusSnapshot(
  enabled: boolean,
  state: string,
  targetVersion: string | null,
  stagedVersion: string | null,
  availableVersion: string | null,
): BackgroundUpdateCheckStatus {
  return {
    enabled,
    state,
    targetVersion,
    stagedVersion,
    availableVersion,
  };
}

export function useBackgroundSystemUpdateCheck(status: SystemUpdateStatusResponse | null | undefined): void {
  const { mutate, isPending } = useCheckSystemUpdate();
  const lastAttemptAtRef = useRef<number | null>(null);
  const enabled = status?.enabled ?? false;
  const state = status?.state ?? 'DISABLED';
  const targetVersion = status?.target?.version ?? null;
  const stagedVersion = status?.staged?.version ?? null;
  const availableVersion = status?.available?.version ?? null;
  const snapshotRef = useRef<UpdateCheckSnapshot>({
    status: buildStatusSnapshot(enabled, state, targetVersion, stagedVersion, availableVersion),
    isCheckPending: isPending,
  });

  useEffect(() => {
    snapshotRef.current = {
      status: buildStatusSnapshot(enabled, state, targetVersion, stagedVersion, availableVersion),
      isCheckPending: isPending,
    };
  }, [availableVersion, enabled, isPending, stagedVersion, state, targetVersion]);

  useEffect(() => {
    attemptBackgroundUpdateCheck(
      buildStatusSnapshot(enabled, state, targetVersion, stagedVersion, availableVersion),
      isPending,
      lastAttemptAtRef,
      mutate,
    );
  }, [availableVersion, enabled, isPending, mutate, stagedVersion, state, targetVersion]);

  useEffect(() => {
    const intervalId = window.setInterval(() => {
      const snapshot = snapshotRef.current;
      attemptBackgroundUpdateCheck(snapshot.status, snapshot.isCheckPending, lastAttemptAtRef, mutate);
    }, AUTO_UPDATE_CHECK_INTERVAL_MS);

    return () => {
      window.clearInterval(intervalId);
    };
  }, [mutate]);
}
