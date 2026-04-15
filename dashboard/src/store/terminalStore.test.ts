/* @vitest-environment jsdom */

import { beforeEach, describe, expect, it } from 'vitest';
import { useTerminalStore } from './terminalStore';

function resetStore(): void {
  useTerminalStore.setState({
    tabs: [],
    activeTabId: null,
    connectionStatus: 'idle',
    pendingInput: {},
  });
}

describe('terminalStore', () => {
  beforeEach(() => {
    resetStore();
  });

  it('starts with no tabs and an idle connection', () => {
    const state = useTerminalStore.getState();
    expect(state.tabs).toEqual([]);
    expect(state.activeTabId).toBeNull();
    expect(state.connectionStatus).toBe('idle');
  });

  it('openTab appends a new tab and activates it', () => {
    const id = useTerminalStore.getState().openTab();
    const state = useTerminalStore.getState();
    expect(state.tabs).toHaveLength(1);
    expect(state.tabs[0]?.id).toBe(id);
    expect(state.tabs[0]?.title).toBe('Terminal 1');
    expect(state.activeTabId).toBe(id);
  });

  it('openTab increments default titles monotonically', () => {
    useTerminalStore.getState().openTab();
    useTerminalStore.getState().openTab();
    const titles = useTerminalStore.getState().tabs.map((tab) => tab.title);
    expect(titles).toEqual(['Terminal 1', 'Terminal 2']);
  });

  it('closeTab removes a tab and shifts active when needed', () => {
    const firstId = useTerminalStore.getState().openTab();
    const secondId = useTerminalStore.getState().openTab();

    useTerminalStore.getState().closeTab(secondId);
    const afterSecondClose = useTerminalStore.getState();
    expect(afterSecondClose.tabs).toHaveLength(1);
    expect(afterSecondClose.activeTabId).toBe(firstId);

    useTerminalStore.getState().closeTab(firstId);
    const afterFirstClose = useTerminalStore.getState();
    expect(afterFirstClose.tabs).toHaveLength(0);
    expect(afterFirstClose.activeTabId).toBeNull();
  });

  it('setActiveTab switches to an existing tab', () => {
    const firstId = useTerminalStore.getState().openTab();
    const secondId = useTerminalStore.getState().openTab();

    useTerminalStore.getState().setActiveTab(firstId);
    expect(useTerminalStore.getState().activeTabId).toBe(firstId);

    useTerminalStore.getState().setActiveTab(secondId);
    expect(useTerminalStore.getState().activeTabId).toBe(secondId);
  });

  it('setActiveTab ignores unknown ids', () => {
    const id = useTerminalStore.getState().openTab();
    useTerminalStore.getState().setActiveTab('does-not-exist');
    expect(useTerminalStore.getState().activeTabId).toBe(id);
  });

  it('setConnectionStatus updates the shared transport state', () => {
    useTerminalStore.getState().setConnectionStatus('connecting');
    expect(useTerminalStore.getState().connectionStatus).toBe('connecting');
    useTerminalStore.getState().setConnectionStatus('connected');
    expect(useTerminalStore.getState().connectionStatus).toBe('connected');
  });

  it('enqueueInput appends input chunks for a specific tab', () => {
    const id = useTerminalStore.getState().openTab();
    useTerminalStore.getState().enqueueInput(id, 'ls -la\n');
    useTerminalStore.getState().enqueueInput(id, 'pwd\n');
    const queue = useTerminalStore.getState().pendingInput[id] ?? [];
    expect(queue).toEqual(['ls -la\n', 'pwd\n']);
  });

  it('consumePendingInput drains queued chunks and clears the tab entry', () => {
    const id = useTerminalStore.getState().openTab();
    useTerminalStore.getState().enqueueInput(id, 'echo a\n');
    useTerminalStore.getState().enqueueInput(id, 'echo b\n');

    const drained = useTerminalStore.getState().consumePendingInput(id);
    expect(drained).toEqual(['echo a\n', 'echo b\n']);
    expect(useTerminalStore.getState().pendingInput[id]).toBeUndefined();
  });

  it('consumePendingInput returns an empty array when no input is queued', () => {
    const id = useTerminalStore.getState().openTab();
    const drained = useTerminalStore.getState().consumePendingInput(id);
    expect(drained).toEqual([]);
  });

  it('closeTab also drops any pending input queued for that tab', () => {
    const id = useTerminalStore.getState().openTab();
    useTerminalStore.getState().enqueueInput(id, 'whoami\n');
    useTerminalStore.getState().closeTab(id);
    expect(useTerminalStore.getState().pendingInput[id]).toBeUndefined();
  });
});
