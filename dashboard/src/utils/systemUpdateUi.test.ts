import { describe, expect, it } from 'vitest';
import type { SystemUpdateStatusResponse } from '../api/system';
import {
  AUTO_UPDATE_CHECK_INTERVAL_MS,
  type BackgroundUpdateCheckStatus,
  formatUpdateTimestamp,
  formatVersionLabel,
  getSidebarUpdateBadge,
  getTopbarUpdateNotice,
  getUpdateActionLabel,
  getUpdateSourceLabel,
  getUpdateStateDescription,
  getUpdateStateLabel,
  getUpdateStateVariant,
  getUpdateWorkflowPresentation,
  hasPendingUpdate,
  shouldCheckSystemUpdateInBackground,
} from './systemUpdateUi';

function buildStatus(overrides: Partial<SystemUpdateStatusResponse> = {}): SystemUpdateStatusResponse {
  return {
    state: 'IDLE',
    enabled: true,
    current: { version: '0.4.0', source: 'image' },
    target: null,
    staged: null,
    available: null,
    lastCheckAt: '2026-02-20T12:00:00Z',
    lastError: null,
    progressPercent: 0,
    stageTitle: 'Running 0.4.0',
    stageDescription: 'No update workflow is running.',
    ...overrides,
  };
}

function buildCheckStatus(overrides: Partial<BackgroundUpdateCheckStatus> = {}): BackgroundUpdateCheckStatus {
  return {
    enabled: true,
    state: 'IDLE',
    targetVersion: null,
    stagedVersion: null,
    availableVersion: null,
    ...overrides,
  };
}

describe('systemUpdateUi', () => {
  it('shouldFormatUpdateTimestampAndVersionWithFallbacks', () => {
    expect(formatUpdateTimestamp(null)).toBe('N/A');
    expect(formatUpdateTimestamp('  ')).toBe('N/A');
    expect(formatUpdateTimestamp('not-a-date')).toBe('not-a-date');
    expect(formatVersionLabel(undefined)).toBe('N/A');

    const formatted = formatUpdateTimestamp('2026-02-20T12:00:00Z');
    expect(formatted).not.toBe('N/A');
  });

  it('shouldResolveUpdateStateMetadata', () => {
    expect(getUpdateStateVariant('FAILED')).toBe('danger');
    expect(getUpdateStateVariant('AVAILABLE')).toBe('warning');
    expect(getUpdateStateVariant('CHECKING')).toBe('info');
    expect(getUpdateStateVariant('DISABLED')).toBe('secondary');
    expect(getUpdateStateLabel('APPLYING')).toBe('Restarting');
    expect(getUpdateStateDescription('FAILED')).toContain('failed');
  });

  it('shouldBuildWorkflowPresentationFromStatus', () => {
    const status = buildStatus({
      state: 'PREPARING',
      target: { version: '0.4.2' },
      available: { version: '0.4.2' },
      progressPercent: 52,
      stageTitle: 'Downloading and verifying 0.4.2',
      stageDescription: 'The release package is being downloaded and validated before restart.',
    });

    const presentation = getUpdateWorkflowPresentation(status);

    expect(presentation.progressPercent).toBe(52);
    expect(presentation.title).toBe('Downloading and verifying 0.4.2');
    expect(presentation.steps).toEqual([
      { key: 'check', label: 'Check', state: 'complete' },
      { key: 'download', label: 'Download', state: 'current' },
      { key: 'stage', label: 'Stage', state: 'upcoming' },
      { key: 'restart', label: 'Restart', state: 'upcoming' },
      { key: 'verify', label: 'Verify', state: 'upcoming' },
    ]);
  });

  it('shouldResolveUpdateActionsAndPendingState', () => {
    const availableStatus = buildStatus({
      state: 'AVAILABLE',
      target: { version: '0.4.2' },
      available: { version: '0.4.2' },
    });
    const stagedStatus = buildStatus({
      state: 'STAGED',
      target: { version: '0.4.2' },
      staged: { version: '0.4.2' },
    });

    expect(hasPendingUpdate(availableStatus)).toBe(true);
    expect(getUpdateActionLabel(availableStatus)).toBe('Update to 0.4.2');
    expect(getUpdateActionLabel(stagedStatus)).toBe('Restart to apply 0.4.2');
    expect(getUpdateActionLabel(buildStatus({ state: 'VERIFYING', target: { version: '0.4.2' } }))).toBe('Restarting...');
    expect(getUpdateSourceLabel('jar')).toBe('Local package');
    expect(getUpdateSourceLabel('image')).toBe('Container image');
  });

  it('shouldDeriveFailedWorkflowStepsAndFallbackProgress', () => {
    const downloadFailure = buildStatus({
      state: 'FAILED',
      target: { version: '0.4.2' },
      available: { version: '0.4.2' },
      progressPercent: null,
      stageTitle: null,
      stageDescription: null,
      lastError: 'Checksum mismatch',
    });
    const restartFailure = buildStatus({
      state: 'FAILED',
      target: { version: '0.4.2' },
      staged: { version: '0.4.2' },
      progressPercent: null,
      stageTitle: null,
      stageDescription: null,
      lastError: 'Restart probe failed',
    });

    const downloadPresentation = getUpdateWorkflowPresentation(downloadFailure);
    const restartPresentation = getUpdateWorkflowPresentation(restartFailure);

    expect(downloadPresentation.progressPercent).toBe(52);
    expect(downloadPresentation.title).toBe('Failed 0.4.2');
    expect(downloadPresentation.steps).toEqual([
      { key: 'check', label: 'Check', state: 'complete' },
      { key: 'download', label: 'Download', state: 'error' },
      { key: 'stage', label: 'Stage', state: 'upcoming' },
      { key: 'restart', label: 'Restart', state: 'upcoming' },
      { key: 'verify', label: 'Verify', state: 'upcoming' },
    ]);
    expect(restartPresentation.steps).toEqual([
      { key: 'check', label: 'Check', state: 'complete' },
      { key: 'download', label: 'Download', state: 'complete' },
      { key: 'stage', label: 'Stage', state: 'complete' },
      { key: 'restart', label: 'Restart', state: 'error' },
      { key: 'verify', label: 'Verify', state: 'upcoming' },
    ]);
  });

  it('shouldBuildSidebarBadgesForImportantStates', () => {
    expect(getSidebarUpdateBadge('AVAILABLE')).toEqual({
      label: 'NEW',
      variant: 'warning',
      text: 'dark',
      title: 'New update is available',
    });
    expect(getSidebarUpdateBadge('STAGED')).toEqual({
      label: 'READY',
      variant: 'info',
      text: 'white',
      title: 'Update is staged and ready to apply',
    });
    expect(getSidebarUpdateBadge('APPLYING')).toEqual({
      label: 'UPD',
      variant: 'info',
      text: 'white',
      title: 'Update is in progress',
    });
    expect(getSidebarUpdateBadge('FAILED')).toEqual({
      label: 'ERR',
      variant: 'danger',
      text: 'white',
      title: 'Last update operation failed',
    });
    expect(getSidebarUpdateBadge('IDLE')).toBeNull();
  });

  it('shouldBuildTopbarNoticesForUpdateStates', () => {
    expect(getTopbarUpdateNotice(buildStatus({
      state: 'AVAILABLE',
      target: { version: '0.4.2' },
      available: { version: '0.4.2' },
    }))).toEqual({
      badge: 'NEW',
      tone: 'warning',
      title: 'Update 0.4.2 is available',
      busy: false,
      emphasis: true,
    });

    expect(getTopbarUpdateNotice(buildStatus({
      state: 'PREPARING',
      target: { version: '0.4.2' },
      available: { version: '0.4.2' },
    }))).toEqual({
      badge: 'UPD',
      tone: 'info',
      title: 'Updating to 0.4.2',
      busy: true,
      emphasis: false,
    });

    expect(getTopbarUpdateNotice(buildStatus({ state: 'FAILED' }))).toEqual({
      badge: 'ERR',
      tone: 'danger',
      title: 'Last update attempt failed',
      busy: false,
      emphasis: true,
    });

    expect(getTopbarUpdateNotice(buildStatus())).toBeNull();
  });

  it('shouldScheduleBackgroundChecksOnlyWhenDueAndSafe', () => {
    const now = 1_000_000;
    const idleStatus = buildCheckStatus();
    const availableStatus = buildCheckStatus({
      state: 'AVAILABLE',
      targetVersion: '0.4.2',
      availableVersion: '0.4.2',
    });

    expect(shouldCheckSystemUpdateInBackground(idleStatus, null, now, false)).toBe(true);
    expect(shouldCheckSystemUpdateInBackground(idleStatus, now - 1_000, now, false)).toBe(false);
    expect(
      shouldCheckSystemUpdateInBackground(
        idleStatus,
        now - AUTO_UPDATE_CHECK_INTERVAL_MS,
        now,
        false,
      ),
    ).toBe(true);
    expect(shouldCheckSystemUpdateInBackground(idleStatus, null, now, true)).toBe(false);
    expect(shouldCheckSystemUpdateInBackground(buildCheckStatus({ enabled: false }), null, now, false)).toBe(false);
    expect(shouldCheckSystemUpdateInBackground(buildCheckStatus({ state: 'CHECKING' }), null, now, false)).toBe(false);
    expect(shouldCheckSystemUpdateInBackground(availableStatus, null, now, false)).toBe(false);
  });
});
