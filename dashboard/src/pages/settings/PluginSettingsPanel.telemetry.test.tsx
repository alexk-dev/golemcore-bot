/* @vitest-environment jsdom */

import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const recordKeyedCounter = vi.hoisted(() => vi.fn());
const executeAction = vi.hoisted(() => vi.fn(() => Promise.resolve({ status: 'ok', message: 'ok' })));
const pluginSection = vi.hoisted(() => ({
  title: 'Browser Plugin',
  description: 'Plugin settings',
  fields: [],
  values: {},
  blocks: [],
  actions: [
    {
      actionId: 'reload',
      label: 'Reload',
      variant: 'secondary',
    },
  ],
}));

vi.mock('react-hot-toast', () => ({
  default: {
    success: vi.fn(),
    error: vi.fn(),
  },
}));

vi.mock('../../lib/telemetry/TelemetryContext', () => ({
  useTelemetry: () => ({
    recordCounter: vi.fn(),
    recordKeyedCounter,
    recordCounterByRoute: vi.fn(),
    recordUiError: vi.fn(),
  }),
}));

vi.mock('../../hooks/usePlugins', () => ({
  usePluginSettingsSection: () => ({
    isLoading: false,
    data: pluginSection,
  }),
  useSavePluginSettingsSection: () => ({
    mutateAsync: vi.fn(() => Promise.resolve()),
    isPending: false,
  }),
  useExecutePluginSettingsAction: () => ({
    mutateAsync: executeAction,
    isPending: false,
  }),
}));

import PluginSettingsPanel from './PluginSettingsPanel';

declare global {
  var IS_REACT_ACT_ENVIRONMENT: boolean | undefined;
}

globalThis.IS_REACT_ACT_ENVIRONMENT = true;

function getButtonByText(label: string): HTMLButtonElement {
  const button = Array.from(document.querySelectorAll('button')).find(
    (element): element is HTMLButtonElement => element.textContent?.trim() === label,
  );
  if (button == null) {
    throw new Error(`Button "${label}" not found`);
  }
  return button;
}

describe('PluginSettingsPanel telemetry', () => {
  afterEach(() => {
    document.body.innerHTML = '';
  });

  beforeEach(() => {
    recordKeyedCounter.mockClear();
    executeAction.mockClear();
  });

  it('records plugin action executions by route', () => {
    const container = document.createElement('div');
    document.body.appendChild(container);
    const root: Root = createRoot(container);

    act(() => {
      root.render(<PluginSettingsPanel routeKey="plugin-browser" />);
    });

    const reloadButton = getButtonByText('Reload');

    act(() => {
      reloadButton.click();
    });

    expect(recordKeyedCounter).toHaveBeenCalledWith('plugin_settings_open_count_by_route', 'plugin-browser');
    expect(recordKeyedCounter).toHaveBeenCalledWith('plugin_action_count_by_route', 'plugin-browser');

    act(() => {
      root.unmount();
    });
  });
});
