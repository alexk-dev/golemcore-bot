import { describe, expect, it } from 'vitest';
import type { SystemUpdateStatusResponse } from '../api/system';
import {
  formatUpdateTimestamp,
  formatVersionLabel,
  getSidebarUpdateBadge,
  getUpdateActionLabel,
  getUpdateSourceLabel,
  getUpdateStateDescription,
  getUpdateStateLabel,
  getUpdateStateVariant,
  getUpdateWorkflowPresentation,
  hasPendingUpdate,
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
    expect(getUpdateSourceLabel('jar')).toBe('Local package');
    expect(getUpdateSourceLabel('image')).toBe('Container image');
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
});
