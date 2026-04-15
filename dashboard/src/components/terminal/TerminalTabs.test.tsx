/* @vitest-environment jsdom */

import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { TerminalTabs } from './TerminalTabs';
import { useTerminalStore } from '../../store/terminalStore';

declare global {
  var IS_REACT_ACT_ENVIRONMENT: boolean | undefined;
}

globalThis.IS_REACT_ACT_ENVIRONMENT = true;

interface Harness {
  container: HTMLDivElement;
  root: Root;
}

function mount(): Harness {
  const container = document.createElement('div');
  document.body.appendChild(container);
  const root = createRoot(container);
  act(() => {
    root.render(<TerminalTabs />);
  });
  return { container, root };
}

function unmount({ container, root }: Harness): void {
  act(() => {
    root.unmount();
  });
  container.remove();
}

function resetStore(): void {
  useTerminalStore.setState({
    tabs: [],
    activeTabId: null,
    connectionStatus: 'idle',
  });
}

describe('TerminalTabs', () => {
  beforeEach(() => {
    resetStore();
  });

  afterEach(() => {
    document.body.innerHTML = '';
    resetStore();
  });

  it('renders an empty-state new-tab button when no tabs exist', () => {
    const harness = mount();
    const newTabButton = harness.container.querySelector<HTMLButtonElement>(
      '[data-testid="terminal-new-tab"]',
    );
    expect(newTabButton).not.toBeNull();
    unmount(harness);
  });

  it('creates a new terminal tab when the new-tab button is clicked', () => {
    const harness = mount();
    const newTabButton = harness.container.querySelector<HTMLButtonElement>(
      '[data-testid="terminal-new-tab"]',
    );
    act(() => {
      newTabButton?.click();
    });
    const state = useTerminalStore.getState();
    expect(state.tabs).toHaveLength(1);
    expect(state.activeTabId).toBe(state.tabs[0]?.id);
    unmount(harness);
  });

  it('marks the active tab and switches when another tab is clicked', () => {
    act(() => {
      useTerminalStore.getState().openTab();
      useTerminalStore.getState().openTab();
    });
    const harness = mount();
    const [firstId] = useTerminalStore.getState().tabs.map((tab) => tab.id);
    const firstButton = harness.container.querySelector<HTMLButtonElement>(
      `[data-testid="terminal-tab-${firstId ?? ''}"]`,
    );
    expect(firstButton).not.toBeNull();
    act(() => {
      firstButton?.click();
    });
    expect(useTerminalStore.getState().activeTabId).toBe(firstId);
    unmount(harness);
  });

  it('closes a tab when its close button is clicked', () => {
    act(() => {
      useTerminalStore.getState().openTab();
      useTerminalStore.getState().openTab();
    });
    const harness = mount();
    const [firstId] = useTerminalStore.getState().tabs.map((tab) => tab.id);
    const closeButton = harness.container.querySelector<HTMLButtonElement>(
      `[data-testid="terminal-tab-close-${firstId ?? ''}"]`,
    );
    act(() => {
      closeButton?.click();
    });
    const state = useTerminalStore.getState();
    expect(state.tabs).toHaveLength(1);
    expect(state.tabs.some((tab) => tab.id === firstId)).toBe(false);
    unmount(harness);
  });
});
