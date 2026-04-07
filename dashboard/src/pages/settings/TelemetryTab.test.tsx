/* @vitest-environment jsdom */

import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import type { TelemetryConfig } from '../../api/settings';
import TelemetryTab from './TelemetryTab';

declare global {
  var IS_REACT_ACT_ENVIRONMENT: boolean | undefined;
}

globalThis.IS_REACT_ACT_ENVIRONMENT = true;

const mutateAsync = vi.fn(() => Promise.resolve());
const toastSuccess = vi.fn();

vi.mock('react-hot-toast', () => ({
  default: {
    success: (...args: unknown[]) => toastSuccess(...args),
  },
}));

vi.mock('../../hooks/useSettings', () => ({
  useUpdateTelemetry: () => ({
    mutateAsync,
    isPending: false,
  }),
}));

interface RenderResult {
  container: HTMLDivElement;
  unmount: () => void;
}

function renderTelemetryTab(config: TelemetryConfig): RenderResult {
  const container = document.createElement('div');
  document.body.appendChild(container);
  const root: Root = createRoot(container);

  act(() => {
    root.render(<TelemetryTab config={config} />);
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

function getTelemetryToggle(container: HTMLElement): HTMLInputElement {
  const input = container.querySelector('input[type="checkbox"]');
  if (!(input instanceof HTMLInputElement)) {
    throw new Error('Telemetry toggle input not found');
  }
  return input;
}

function getButtonByText(label: string): HTMLButtonElement {
  const buttons = Array.from(document.querySelectorAll('button'));
  const match = buttons.find((button) => button.textContent?.trim() === label);
  if (!(match instanceof HTMLButtonElement)) {
    throw new Error(`Button "${label}" not found`);
  }
  return match;
}

async function click(element: HTMLElement): Promise<void> {
  await act(async () => {
    element.click();
  });
}

async function flushPromises(): Promise<void> {
  await act(async () => {
    await Promise.resolve();
  });
}

describe('TelemetryTab', () => {
  afterEach(() => {
    document.body.innerHTML = '';
  });

  beforeEach(() => {
    mutateAsync.mockClear();
    toastSuccess.mockClear();
  });

  it('renders anonymous telemetry disclosure copy', () => {
    const view = renderTelemetryTab({ enabled: true });

    expect(view.container.textContent).toContain('Enable anonymous telemetry');
    expect(view.container.textContent).toContain('All data is anonymous');
    expect(view.container.textContent).toContain('Only aggregated product statistics and UI error summaries are sent');

    view.unmount();
  });

  it('shows a discouraging confirmation modal before disabling telemetry', async () => {
    const view = renderTelemetryTab({ enabled: true });
    const toggle = getTelemetryToggle(view.container);

    expect(toggle.checked).toBe(true);

    await click(toggle);

    const pageText = document.body.textContent ?? '';

    expect(pageText).toContain('Turn off anonymous telemetry?');
    expect(pageText).toContain('UI errors will no longer be collected');
    expect(pageText.toLowerCase()).toContain('open source products have limited resources');
    expect(toggle.checked).toBe(true);

    await click(getButtonByText('Keep telemetry enabled'));

    expect(document.body.textContent ?? '').not.toContain('Turn off anonymous telemetry?');
    expect(getTelemetryToggle(view.container).checked).toBe(true);

    view.unmount();
  });

  it('disables telemetry only after explicit confirmation and saves the new config', async () => {
    const view = renderTelemetryTab({ enabled: true });

    await click(getTelemetryToggle(view.container));
    await click(getButtonByText('Disable anyway'));

    expect(getTelemetryToggle(view.container).checked).toBe(false);

    await click(getButtonByText('Save'));
    await flushPromises();

    expect(mutateAsync).toHaveBeenCalledWith({ enabled: false });
    expect(toastSuccess).toHaveBeenCalledWith('Telemetry settings saved');

    view.unmount();
  });
});
