/* @vitest-environment jsdom */

import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const recordCounter = vi.hoisted(() => vi.fn());
const installMutateAsync = vi.hoisted(() => vi.fn(() => Promise.resolve({ message: 'Installed' })));
const uninstallMutateAsync = vi.hoisted(() => vi.fn(() => Promise.resolve({ message: 'Uninstalled' })));

vi.mock('react-hot-toast', () => ({
  default: {
    success: vi.fn(),
    error: vi.fn(),
  },
}));

vi.mock('../../lib/telemetry/TelemetryProvider', () => ({
  useTelemetry: () => ({
    recordCounter,
    recordKeyedCounter: vi.fn(),
    recordCounterByRoute: vi.fn(),
    recordUiError: vi.fn(),
  }),
}));

vi.mock('../../hooks/usePlugins', () => ({
  usePluginMarketplace: () => ({
    isLoading: false,
    isError: false,
    data: {
      available: true,
      items: [
        {
          id: 'golemcore/browser',
          provider: 'golemcore',
          name: 'Browser',
          description: 'Web browsing',
          version: '1.0.0',
          maintainers: [],
          official: true,
          compatible: true,
          artifactAvailable: true,
          installed: false,
          loaded: false,
          updateAvailable: false,
          settingsRouteKey: 'plugin-browser',
        },
        {
          id: 'golemcore/weather',
          provider: 'golemcore',
          name: 'Weather',
          description: 'Weather provider',
          version: '1.0.0',
          maintainers: [],
          official: true,
          compatible: true,
          artifactAvailable: true,
          installed: true,
          loaded: true,
          updateAvailable: false,
          installedVersion: '1.0.0',
          settingsRouteKey: 'plugin-weather',
        },
      ],
    },
  }),
  useInstallPluginFromMarketplace: () => ({
    isPending: false,
    variables: null,
    mutateAsync: installMutateAsync,
  }),
  useUninstallPluginFromMarketplace: () => ({
    isPending: false,
    variables: null,
    mutateAsync: uninstallMutateAsync,
  }),
}));

import PluginsMarketplaceTab from './PluginsMarketplaceTab';

declare global {
  var IS_REACT_ACT_ENVIRONMENT: boolean | undefined;
}

globalThis.IS_REACT_ACT_ENVIRONMENT = true;

function getButtonsByText(label: string): HTMLButtonElement[] {
  return Array.from(document.querySelectorAll('button')).filter(
    (button): button is HTMLButtonElement => button.textContent?.trim() === label,
  );
}

function findButtonByText(pattern: RegExp): HTMLButtonElement {
  const button = Array.from(document.querySelectorAll('button')).find(
    (element): element is HTMLButtonElement => pattern.test(element.textContent?.trim() ?? ''),
  );
  if (button == null) {
    throw new Error(`Button not found for pattern ${pattern}`);
  }
  return button;
}

describe('PluginsMarketplaceTab telemetry', () => {
  afterEach(() => {
    document.body.innerHTML = '';
  });

  beforeEach(() => {
    recordCounter.mockClear();
    installMutateAsync.mockClear();
    uninstallMutateAsync.mockClear();
  });

  it('records plugin install and uninstall intent counts', () => {
    const container = document.createElement('div');
    document.body.appendChild(container);
    const root: Root = createRoot(container);

    act(() => {
      root.render(
        <MemoryRouter>
          <PluginsMarketplaceTab />
        </MemoryRouter>,
      );
    });

    expect(recordCounter).toHaveBeenCalledWith('plugin_marketplace_open_count');

    const [installButton] = getButtonsByText('Install');
    if (installButton == null) {
      throw new Error('Install button not found');
    }
    const uninstallButton = findButtonByText(/uninstall/i);

    act(() => {
      installButton.click();
      uninstallButton.click();
    });

    expect(recordCounter).toHaveBeenCalledWith('plugin_install_intent_count');
    expect(recordCounter).toHaveBeenCalledWith('plugin_uninstall_intent_count');

    act(() => {
      root.unmount();
    });
  });
});
