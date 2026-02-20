import type {
  RollbackConfirmRequest,
  RollbackIntentRequest,
  SystemUpdateIntentResponse,
  SystemUpdateStatusResponse,
} from '../../../api/system';
import { UPDATE_BUSY_STATES } from '../../../utils/systemUpdateUi';

export function isIntentExpired(expiresAt: string, nowMs: number = Date.now()): boolean {
  const timestamp = Date.parse(expiresAt);
  if (Number.isNaN(timestamp)) {
    return false;
  }
  return timestamp <= nowMs;
}

export function hasPendingOperations(flags: boolean[]): boolean {
  return flags.some((flag) => flag);
}

export function resolveEnabled(status: SystemUpdateStatusResponse | null, hasStatusError: boolean): boolean {
  if (status?.enabled != null) {
    return status.enabled;
  }
  return !hasStatusError;
}

export function resolveCanPrepare(availableVersion: string | null, stagedVersion: string | null): boolean {
  return availableVersion != null || stagedVersion != null;
}

export function resolveCanRequestApply(stagedVersion: string | null): boolean {
  return stagedVersion != null;
}

export function resolveOperationBusy(flags: boolean[], state: string): boolean {
  return hasPendingOperations(flags) || UPDATE_BUSY_STATES.has(state);
}

export function resolveHistoryLoading(isLoading: boolean, isFetching: boolean): boolean {
  return isLoading || isFetching;
}

export function resolveConfirmPending(isApplyPending: boolean, isRollbackPending: boolean): boolean {
  return isApplyPending || isRollbackPending;
}

export function buildRollbackIntentPayload(rollbackVersion: string): RollbackIntentRequest | undefined {
  const version = rollbackVersion.trim();
  return version.length > 0 ? { version } : undefined;
}

export function buildRollbackConfirmPayload(intent: SystemUpdateIntentResponse, token: string): RollbackConfirmRequest {
  if (intent.targetVersion != null && intent.targetVersion.trim().length > 0) {
    return { confirmToken: token, version: intent.targetVersion };
  }
  return { confirmToken: token };
}
