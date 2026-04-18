import { create } from 'zustand';

const STORAGE_KEY = 'golem-workspace-layout';
const MIN_PANEL_SIZE = 10;
const MAX_PANEL_SIZE = 80;

export type WorkspaceCompactPane = 'editor' | 'chat';

export interface WorkspaceLayoutSnapshot {
  sidebarSize: number;
  chatSize: number;
  terminalSize: number;
  isChatVisible: boolean;
  isTerminalVisible: boolean;
  compactActivePane: WorkspaceCompactPane;
  isCompactTerminalVisible: boolean;
}

export const DEFAULT_WORKSPACE_LAYOUT: WorkspaceLayoutSnapshot = {
  sidebarSize: 20,
  chatSize: 30,
  terminalSize: 30,
  isChatVisible: true,
  isTerminalVisible: false,
  compactActivePane: 'editor',
  isCompactTerminalVisible: false,
};

interface WorkspaceLayoutState extends WorkspaceLayoutSnapshot {
  setChatSize: (size: number) => void;
  setTerminalSize: (size: number) => void;
  setChatVisible: (visible: boolean) => void;
  setTerminalVisible: (visible: boolean) => void;
  setCompactPane: (pane: WorkspaceCompactPane) => void;
  setCompactTerminalVisible: (visible: boolean) => void;
  toggleChat: () => void;
  toggleTerminal: () => void;
  toggleCompactTerminal: () => void;
}

function clampSize(size: number, fallback: number): number {
  if (!Number.isFinite(size)) {
    return fallback;
  }
  if (size < MIN_PANEL_SIZE) {
    return MIN_PANEL_SIZE;
  }
  if (size > MAX_PANEL_SIZE) {
    return MAX_PANEL_SIZE;
  }
  return size;
}

function pickNumber(
  source: Record<string, unknown>,
  key: keyof WorkspaceLayoutSnapshot,
  fallback: number,
): number {
  const value = source[key];
  if (typeof value !== 'number' || !Number.isFinite(value)) {
    return fallback;
  }
  return clampSize(value, fallback);
}

function pickBoolean(
  source: Record<string, unknown>,
  key: keyof WorkspaceLayoutSnapshot,
  fallback: boolean,
): boolean {
  const value = source[key];
  if (typeof value !== 'boolean') {
    return fallback;
  }
  return value;
}

function isWorkspaceCompactPane(value: unknown): value is WorkspaceCompactPane {
  return value === 'editor' || value === 'chat';
}

function pickCompactPane(
  source: Record<string, unknown>,
  fallback: WorkspaceCompactPane,
): WorkspaceCompactPane {
  const value = source.compactActivePane;
  if (!isWorkspaceCompactPane(value)) {
    return fallback;
  }
  return value;
}

/**
 * Parses and sanitizes the persisted workspace layout snapshot from localStorage.
 */
export function normalizeStoredWorkspaceLayout(raw: string | null): WorkspaceLayoutSnapshot {
  if (raw == null || raw.length === 0) {
    return { ...DEFAULT_WORKSPACE_LAYOUT };
  }
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch (error) {
    console.error('Failed to parse workspace layout from storage', error);
    return { ...DEFAULT_WORKSPACE_LAYOUT };
  }
  if (parsed == null || typeof parsed !== 'object' || Array.isArray(parsed)) {
    return { ...DEFAULT_WORKSPACE_LAYOUT };
  }
  const source = parsed as Record<string, unknown>;
  return {
    sidebarSize: pickNumber(source, 'sidebarSize', DEFAULT_WORKSPACE_LAYOUT.sidebarSize),
    chatSize: pickNumber(source, 'chatSize', DEFAULT_WORKSPACE_LAYOUT.chatSize),
    terminalSize: pickNumber(source, 'terminalSize', DEFAULT_WORKSPACE_LAYOUT.terminalSize),
    isChatVisible: pickBoolean(source, 'isChatVisible', DEFAULT_WORKSPACE_LAYOUT.isChatVisible),
    isTerminalVisible: pickBoolean(
      source,
      'isTerminalVisible',
      DEFAULT_WORKSPACE_LAYOUT.isTerminalVisible,
    ),
    compactActivePane: pickCompactPane(source, DEFAULT_WORKSPACE_LAYOUT.compactActivePane),
    isCompactTerminalVisible: pickBoolean(
      source,
      'isCompactTerminalVisible',
      DEFAULT_WORKSPACE_LAYOUT.isCompactTerminalVisible,
    ),
  };
}

function persistSnapshot(snapshot: WorkspaceLayoutSnapshot): void {
  if (typeof window === 'undefined') {
    return;
  }
  try {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(snapshot));
  } catch (error) {
    console.error('Failed to persist workspace layout', error);
  }
}

function readInitialLayout(): WorkspaceLayoutSnapshot {
  if (typeof window === 'undefined') {
    return { ...DEFAULT_WORKSPACE_LAYOUT };
  }
  const raw = window.localStorage.getItem(STORAGE_KEY);
  const normalized = normalizeStoredWorkspaceLayout(raw);
  persistSnapshot(normalized);
  return normalized;
}

function snapshotFromState(state: WorkspaceLayoutState): WorkspaceLayoutSnapshot {
  return {
    sidebarSize: state.sidebarSize,
    chatSize: state.chatSize,
    terminalSize: state.terminalSize,
    isChatVisible: state.isChatVisible,
    isTerminalVisible: state.isTerminalVisible,
    compactActivePane: state.compactActivePane,
    isCompactTerminalVisible: state.isCompactTerminalVisible,
  };
}

const initialLayout = readInitialLayout();

function persistNextState(get: () => WorkspaceLayoutState): void {
  persistSnapshot(snapshotFromState(get()));
}

/**
 * Persists the workspace panel arrangement for desktop splits and compact pane state.
 */
export const useWorkspaceLayoutStore = create<WorkspaceLayoutState>((set, get) => ({
  ...initialLayout,

  setChatSize: (size: number): void => {
    const next = clampSize(size, DEFAULT_WORKSPACE_LAYOUT.chatSize);
    set({ chatSize: next });
    persistNextState(get);
  },

  setTerminalSize: (size: number): void => {
    const next = clampSize(size, DEFAULT_WORKSPACE_LAYOUT.terminalSize);
    set({ terminalSize: next });
    persistNextState(get);
  },

  setChatVisible: (visible: boolean): void => {
    set({ isChatVisible: visible });
    persistNextState(get);
  },

  setTerminalVisible: (visible: boolean): void => {
    set({ isTerminalVisible: visible });
    persistNextState(get);
  },

  setCompactPane: (pane: WorkspaceCompactPane): void => {
    set({ compactActivePane: pane });
    persistNextState(get);
  },

  setCompactTerminalVisible: (visible: boolean): void => {
    set({ isCompactTerminalVisible: visible });
    persistNextState(get);
  },

  toggleChat: (): void => {
    set({ isChatVisible: !get().isChatVisible });
    persistNextState(get);
  },

  toggleTerminal: (): void => {
    set({ isTerminalVisible: !get().isTerminalVisible });
    persistNextState(get);
  },

  toggleCompactTerminal: (): void => {
    set({ isCompactTerminalVisible: !get().isCompactTerminalVisible });
    persistNextState(get);
  },
}));
