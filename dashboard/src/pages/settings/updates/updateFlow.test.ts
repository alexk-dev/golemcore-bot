import { describe, expect, it } from 'vitest';
import type { SystemUpdateIntentResponse, SystemUpdateStatusResponse } from '../../../api/system';
import {
  buildRollbackConfirmPayload,
  buildRollbackIntentPayload,
  hasPendingOperations,
  isIntentExpired,
  resolveCanPrepare,
  resolveCanRequestApply,
  resolveConfirmPending,
  resolveEnabled,
  resolveHistoryLoading,
  resolveOperationBusy,
} from './updateFlow';

function createStatus(enabled: boolean): SystemUpdateStatusResponse {
  return {
    state: 'IDLE',
    enabled,
    current: null,
    staged: null,
    available: null,
    lastCheckAt: null,
    lastError: null,
  };
}

describe('updateFlow', () => {
  it('shouldTreatIntentAsExpiredWhenNowEqualsExpiry', () => {
    const expiry = '2026-02-20T12:00:00Z';
    const now = Date.parse(expiry);

    expect(isIntentExpired(expiry, now)).toBe(true);
    expect(isIntentExpired(expiry, now - 1)).toBe(false);
  });

  it('shouldTreatInvalidIntentExpiryAsNotExpired', () => {
    expect(isIntentExpired('not-a-date', Date.parse('2026-02-20T12:00:00Z'))).toBe(false);
  });

  it('shouldResolveEnabledFromStatusBeforeErrorFallback', () => {
    expect(resolveEnabled(createStatus(true), true)).toBe(true);
    expect(resolveEnabled(createStatus(false), false)).toBe(false);
    expect(resolveEnabled(null, false)).toBe(true);
    expect(resolveEnabled(null, true)).toBe(false);
  });

  it('shouldResolvePrepareAndApplyAvailability', () => {
    expect(resolveCanPrepare('0.3.1', null)).toBe(true);
    expect(resolveCanPrepare(null, '0.3.1')).toBe(true);
    expect(resolveCanPrepare(null, null)).toBe(false);
    expect(resolveCanRequestApply('0.3.1')).toBe(true);
    expect(resolveCanRequestApply(null)).toBe(false);
  });

  it('shouldResolveBusyFlagsFromPendingOperationsAndState', () => {
    expect(hasPendingOperations([false, true, false])).toBe(true);
    expect(hasPendingOperations([false, false])).toBe(false);

    expect(resolveOperationBusy([false, false], 'IDLE')).toBe(false);
    expect(resolveOperationBusy([true, false], 'IDLE')).toBe(true);
    expect(resolveOperationBusy([false, false], 'PREPARING')).toBe(true);
  });

  it('shouldResolveHistoryAndConfirmPendingFlags', () => {
    expect(resolveHistoryLoading(true, false)).toBe(true);
    expect(resolveHistoryLoading(false, true)).toBe(true);
    expect(resolveHistoryLoading(false, false)).toBe(false);

    expect(resolveConfirmPending(true, false)).toBe(true);
    expect(resolveConfirmPending(false, true)).toBe(true);
    expect(resolveConfirmPending(false, false)).toBe(false);
  });

  it('shouldBuildRollbackIntentPayloadOnlyForNonBlankVersion', () => {
    expect(buildRollbackIntentPayload(' 0.3.0 ')).toEqual({ version: '0.3.0' });
    expect(buildRollbackIntentPayload('')).toBeUndefined();
    expect(buildRollbackIntentPayload('   ')).toBeUndefined();
  });

  it('shouldBuildRollbackConfirmPayloadFromIntentTargetVersion', () => {
    const rollbackWithVersion: SystemUpdateIntentResponse = {
      operation: 'rollback',
      targetVersion: '0.2.9',
      confirmToken: 'ABC123',
      expiresAt: '2026-02-20T12:00:00Z',
    };
    const rollbackToImage: SystemUpdateIntentResponse = {
      operation: 'rollback',
      targetVersion: null,
      confirmToken: 'ABC123',
      expiresAt: '2026-02-20T12:00:00Z',
    };

    expect(buildRollbackConfirmPayload(rollbackWithVersion, 'ABC123')).toEqual({
      confirmToken: 'ABC123',
      version: '0.2.9',
    });
    expect(buildRollbackConfirmPayload(rollbackToImage, 'ABC123')).toEqual({
      confirmToken: 'ABC123',
    });
  });
});
