/* @vitest-environment jsdom */

import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import {
  DEFAULT_WORKSPACE_LAYOUT,
  normalizeStoredWorkspaceLayout,
  useWorkspaceLayoutStore,
} from './workspaceLayoutStore';

const STORAGE_KEY = 'golem-workspace-layout';

function resetStore(): void {
  useWorkspaceLayoutStore.setState({ ...DEFAULT_WORKSPACE_LAYOUT });
}

describe('workspaceLayoutStore', () => {
  beforeEach(() => {
    window.localStorage.clear();
    resetStore();
  });

  afterEach(() => {
    window.localStorage.clear();
  });

  it('exposes sensible defaults', () => {
    const state = useWorkspaceLayoutStore.getState();
    expect(state.chatSize).toBe(DEFAULT_WORKSPACE_LAYOUT.chatSize);
    expect(state.terminalSize).toBe(DEFAULT_WORKSPACE_LAYOUT.terminalSize);
    expect(state.isChatVisible).toBe(true);
    expect(state.isTerminalVisible).toBe(false);
    expect(state.compactActivePane).toBe('editor');
    expect(state.isCompactTerminalVisible).toBe(false);
  });

  it('setChatSize clamps values and persists', () => {
    useWorkspaceLayoutStore.getState().setChatSize(42);
    expect(useWorkspaceLayoutStore.getState().chatSize).toBe(42);

    useWorkspaceLayoutStore.getState().setChatSize(5);
    expect(useWorkspaceLayoutStore.getState().chatSize).toBe(10);

    useWorkspaceLayoutStore.getState().setChatSize(95);
    expect(useWorkspaceLayoutStore.getState().chatSize).toBe(80);

    const raw = window.localStorage.getItem(STORAGE_KEY);
    expect(raw).not.toBeNull();
    const parsed = JSON.parse(raw ?? '{}') as { chatSize: number };
    expect(parsed.chatSize).toBe(80);
  });

  it('setChatSize and setTerminalSize clamp NaN to per-field defaults', () => {
    useWorkspaceLayoutStore.getState().setChatSize(Number.NaN);
    useWorkspaceLayoutStore.getState().setTerminalSize(Number.NaN);

    const state = useWorkspaceLayoutStore.getState();
    expect(state.chatSize).toBe(DEFAULT_WORKSPACE_LAYOUT.chatSize);
    expect(state.terminalSize).toBe(DEFAULT_WORKSPACE_LAYOUT.terminalSize);
  });

  it('setTerminalSize clamps and persists', () => {
    useWorkspaceLayoutStore.getState().setTerminalSize(33);

    const state = useWorkspaceLayoutStore.getState();
    expect(state.terminalSize).toBe(33);

    const raw = window.localStorage.getItem(STORAGE_KEY);
    const parsed = JSON.parse(raw ?? '{}') as { terminalSize: number };
    expect(parsed.terminalSize).toBe(33);
  });

  it('toggleChat flips visibility and persists', () => {
    const initial = useWorkspaceLayoutStore.getState().isChatVisible;
    useWorkspaceLayoutStore.getState().toggleChat();
    expect(useWorkspaceLayoutStore.getState().isChatVisible).toBe(!initial);

    const raw = window.localStorage.getItem(STORAGE_KEY);
    const parsed = JSON.parse(raw ?? '{}') as { isChatVisible: boolean };
    expect(parsed.isChatVisible).toBe(!initial);
  });

  it('toggleTerminal flips visibility and persists', () => {
    useWorkspaceLayoutStore.getState().toggleTerminal();
    expect(useWorkspaceLayoutStore.getState().isTerminalVisible).toBe(true);

    useWorkspaceLayoutStore.getState().toggleTerminal();
    expect(useWorkspaceLayoutStore.getState().isTerminalVisible).toBe(false);
  });

  it('setChatVisible and setTerminalVisible set explicit values', () => {
    useWorkspaceLayoutStore.getState().setChatVisible(false);
    useWorkspaceLayoutStore.getState().setTerminalVisible(true);

    const state = useWorkspaceLayoutStore.getState();
    expect(state.isChatVisible).toBe(false);
    expect(state.isTerminalVisible).toBe(true);
  });

  it('setCompactPane stores the compact active pane and persists it', () => {
    useWorkspaceLayoutStore.getState().setCompactPane('chat');

    const state = useWorkspaceLayoutStore.getState();
    expect(state.compactActivePane).toBe('chat');

    const raw = window.localStorage.getItem(STORAGE_KEY);
    const parsed = JSON.parse(raw ?? '{}') as { compactActivePane: string };
    expect(parsed.compactActivePane).toBe('chat');
  });

  it('toggleCompactTerminal persists compact terminal visibility', () => {
    useWorkspaceLayoutStore.getState().toggleCompactTerminal();
    expect(useWorkspaceLayoutStore.getState().isCompactTerminalVisible).toBe(true);

    const raw = window.localStorage.getItem(STORAGE_KEY);
    const parsed = JSON.parse(raw ?? '{}') as { isCompactTerminalVisible: boolean };
    expect(parsed.isCompactTerminalVisible).toBe(true);
  });
});

describe('normalizeStoredWorkspaceLayout', () => {
  it('returns defaults when storage is empty', () => {
    expect(normalizeStoredWorkspaceLayout(null)).toEqual(DEFAULT_WORKSPACE_LAYOUT);
    expect(normalizeStoredWorkspaceLayout('')).toEqual(DEFAULT_WORKSPACE_LAYOUT);
  });

  it('returns defaults on invalid JSON', () => {
    expect(normalizeStoredWorkspaceLayout('not-json')).toEqual(DEFAULT_WORKSPACE_LAYOUT);
  });

  it('returns defaults when parsed value is not an object', () => {
    expect(normalizeStoredWorkspaceLayout('[1,2,3]')).toEqual(DEFAULT_WORKSPACE_LAYOUT);
    expect(normalizeStoredWorkspaceLayout('"hello"')).toEqual(DEFAULT_WORKSPACE_LAYOUT);
  });

  it('clamps numeric sizes and falls back on wrong types', () => {
    const raw = JSON.stringify({
      sidebarSize: 5,
      chatSize: 150,
      terminalSize: 'oops',
      isChatVisible: 'yes',
      isTerminalVisible: true,
      compactActivePane: 'invalid',
      isCompactTerminalVisible: 'invalid',
    });

    const result = normalizeStoredWorkspaceLayout(raw);

    expect(result.sidebarSize).toBe(10);
    expect(result.chatSize).toBe(80);
    expect(result.terminalSize).toBe(DEFAULT_WORKSPACE_LAYOUT.terminalSize);
    expect(result.isChatVisible).toBe(DEFAULT_WORKSPACE_LAYOUT.isChatVisible);
    expect(result.isTerminalVisible).toBe(true);
    expect(result.compactActivePane).toBe('editor');
    expect(result.isCompactTerminalVisible).toBe(false);
  });

  it('preserves valid fields verbatim', () => {
    const raw = JSON.stringify({
      sidebarSize: 22,
      chatSize: 28,
      terminalSize: 35,
      isChatVisible: false,
      isTerminalVisible: true,
      compactActivePane: 'chat',
      isCompactTerminalVisible: true,
    });

    expect(normalizeStoredWorkspaceLayout(raw)).toEqual({
      sidebarSize: 22,
      chatSize: 28,
      terminalSize: 35,
      isChatVisible: false,
      isTerminalVisible: true,
      compactActivePane: 'chat',
      isCompactTerminalVisible: true,
    });
  });
});
