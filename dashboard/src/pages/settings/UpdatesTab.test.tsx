/* @vitest-environment jsdom */

import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import type { SystemUpdateConfigResponse, SystemUpdateStatusResponse } from '../../api/system';
import { UpdatesTab } from './UpdatesTab';

declare global {
  var IS_REACT_ACT_ENVIRONMENT: boolean | undefined;
}

globalThis.IS_REACT_ACT_ENVIRONMENT = true;

const checkMutateAsync = vi.hoisted(() => vi.fn<() => Promise<{ message: string }>>(() => Promise.resolve({ message: 'Checked' })));
const updateMutateAsync = vi.hoisted(() => vi.fn<() => Promise<{ message: string }>>(() => Promise.resolve({ message: 'Updated' })));
const forceInstallMutateAsync = vi.hoisted(() => vi.fn<() => Promise<{ message: string }>>(() => Promise.resolve({ message: 'Force installed' })));
const updateConfigMutateAsync = vi.hoisted(() => vi.fn(() => Promise.resolve()));
const refetchStatus = vi.hoisted(() => vi.fn(() => Promise.resolve()));
const refetchConfig = vi.hoisted(() => vi.fn(() => Promise.resolve()));
const toastSuccess = vi.hoisted(() => vi.fn<(message: string) => void>());
const toastError = vi.hoisted(() => vi.fn<(message: string) => void>());
const statusState = vi.hoisted(() => ({
  data: {} as SystemUpdateStatusResponse,
  isLoading: false,
  isError: false,
  refetch: refetchStatus,
}));
const configState = vi.hoisted(() => ({
  data: {} as SystemUpdateConfigResponse,
  isLoading: false,
  isError: false,
  refetch: refetchConfig,
}));

vi.mock('react-hot-toast', () => ({
  default: {
    success: toastSuccess,
    error: toastError,
  },
}));

vi.mock('../../hooks/useUpdateAutoReload', () => ({
  useUpdateAutoReload: () => ({ hasSeenDowntime: false, lastProbeError: null }),
}));

vi.mock('../../hooks/useSystem', () => ({
  useSystemUpdateStatus: () => statusState,
  useSystemUpdateConfig: () => configState,
  useCheckSystemUpdate: () => ({ isPending: false, mutateAsync: checkMutateAsync }),
  useUpdateSystemNow: () => ({ isPending: false, mutateAsync: updateMutateAsync }),
  useForceInstallStagedUpdate: () => ({ isPending: false, mutateAsync: forceInstallMutateAsync }),
  useUpdateSystemConfig: () => ({ isPending: false, mutateAsync: updateConfigMutateAsync }),
}));

interface RenderResult {
  container: HTMLDivElement;
  unmount: () => void;
}

function buildStatus(overrides: Partial<SystemUpdateStatusResponse> = {}): SystemUpdateStatusResponse {
  return {
    state: 'WAITING_FOR_IDLE',
    enabled: true,
    autoEnabled: true,
    maintenanceWindowEnabled: false,
    current: { version: '0.4.0', source: 'image' },
    target: { version: '0.4.2' },
    staged: { version: '0.4.2' },
    available: null,
    busy: true,
    blockedReason: 'SESSION_WORK_RUNNING',
    lastCheckAt: '2026-02-20T12:00:00Z',
    lastError: null,
    progressPercent: 80,
    stageTitle: 'Waiting for running work 0.4.2',
    stageDescription: 'The release is staged and will be applied once active session or auto-mode work finishes.',
    ...overrides,
  };
}

function buildConfig(): SystemUpdateConfigResponse {
  return {
    autoEnabled: true,
    checkIntervalMinutes: 60,
    maintenanceWindowEnabled: false,
    maintenanceWindowStartUtc: '00:00',
    maintenanceWindowEndUtc: '00:00',
  };
}

function renderUpdatesTab(): RenderResult {
  const container = document.createElement('div');
  document.body.appendChild(container);
  const root: Root = createRoot(container);

  act(() => {
    root.render(<UpdatesTab />);
  });

  return {
    container,
    unmount: () => {
      act(() => {
        root.unmount();
      });
      container.remove();
    },
  };
}

function getButtonByText(label: string): HTMLButtonElement {
  const buttons = Array.from(document.querySelectorAll('button'));
  const match = buttons.find((button) => button.textContent?.trim() === label);
  if (!(match instanceof HTMLButtonElement)) {
    throw new Error(`Button "${label}" not found`);
  }
  return match;
}

function click(element: HTMLElement): void {
  act(() => {
    element.click();
  });
}

async function flushPromises(): Promise<void> {
  await act(async () => {
    await Promise.resolve();
  });
}

describe('UpdatesTab', () => {
  beforeEach(() => {
    checkMutateAsync.mockClear();
    updateMutateAsync.mockClear();
    forceInstallMutateAsync.mockClear();
    updateConfigMutateAsync.mockClear();
    refetchStatus.mockClear();
    refetchConfig.mockClear();
    toastSuccess.mockClear();
    toastError.mockClear();
    statusState.data = buildStatus();
    configState.data = buildConfig();
  });

  afterEach(() => {
    document.body.innerHTML = '';
    document.body.style.overflow = '';
  });

  it('shows force install affordance and human-readable blocker when staged update is blocked by activity', () => {
    const view = renderUpdatesTab();
    const pageText = document.body.textContent ?? '';

    expect(pageText).toContain('Force install now');
    expect(pageText).toContain('An active or queued session is still running');
    expect(pageText).toContain('The update package is already staged locally');

    view.unmount();
  });

  it('confirms before force installing and calls the dedicated mutation', async () => {
    const view = renderUpdatesTab();

    click(getButtonByText('Force install now'));

    expect(document.body.textContent ?? '').toContain('Force install staged update?');
    expect(document.body.textContent ?? '').toContain('may interrupt the current work');

    click(getButtonByText('Force install'));
    await flushPromises();

    expect(forceInstallMutateAsync).toHaveBeenCalledTimes(1);
    expect(toastSuccess).toHaveBeenCalledWith('Force installed');

    view.unmount();
  });

  it('hides force install action when no staged update is available', () => {
    statusState.data = buildStatus({
      staged: null,
      target: null,
      available: null,
    });

    const view = renderUpdatesTab();

    expect(document.body.textContent ?? '').not.toContain('Force install now');

    view.unmount();
  });
});
