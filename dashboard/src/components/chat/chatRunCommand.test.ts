/* @vitest-environment jsdom */

import { beforeEach, describe, expect, it } from 'vitest';
import { parseRunCommand, routeRunCommandToTerminal } from './chatRunCommand';
import { useTerminalStore } from '../../store/terminalStore';
import { useWorkspaceLayoutStore } from '../../store/workspaceLayoutStore';

function resetTerminal(): void {
  useTerminalStore.setState({
    tabs: [],
    activeTabId: null,
    connectionStatus: 'idle',
    pendingInput: {},
  });
}

function resetLayout(): void {
  useWorkspaceLayoutStore.setState({
    sidebarSize: 20,
    chatSize: 30,
    terminalSize: 30,
    isChatVisible: true,
    isTerminalVisible: false,
  });
}

describe('parseRunCommand', () => {
  it('returns null for plain text', () => {
    expect(parseRunCommand('hello world')).toBeNull();
  });

  it('returns null for a bare /run token', () => {
    expect(parseRunCommand('/run')).toBeNull();
  });

  it('returns null for /run without an argument', () => {
    expect(parseRunCommand('/run   ')).toBeNull();
  });

  it('extracts the command after /run with a single space', () => {
    expect(parseRunCommand('/run ls -la')).toBe('ls -la');
  });

  it('trims surrounding whitespace around the command', () => {
    expect(parseRunCommand('  /run   echo hi  ')).toBe('echo hi');
  });

  it('does not match commands embedded in text', () => {
    expect(parseRunCommand('please /run ls')).toBeNull();
  });
});

describe('routeRunCommandToTerminal', () => {
  beforeEach(() => {
    resetTerminal();
    resetLayout();
  });

  it('opens a new terminal tab when none exist and queues the command with a newline', () => {
    routeRunCommandToTerminal('ls -la');

    const terminal = useTerminalStore.getState();
    expect(terminal.tabs).toHaveLength(1);
    const newTabId = terminal.tabs[0]?.id;
    expect(newTabId).toBeDefined();
    if (newTabId != null) {
      expect(terminal.pendingInput[newTabId]).toEqual(['ls -la\n']);
    }
  });

  it('reveals the terminal panel when the command is routed', () => {
    routeRunCommandToTerminal('pwd');
    expect(useWorkspaceLayoutStore.getState().isTerminalVisible).toBe(true);
  });

  it('routes to the already active tab without opening a new one', () => {
    const existingId = useTerminalStore.getState().openTab();
    routeRunCommandToTerminal('whoami');

    const terminal = useTerminalStore.getState();
    expect(terminal.tabs).toHaveLength(1);
    expect(terminal.pendingInput[existingId]).toEqual(['whoami\n']);
  });
});
