import { create } from 'zustand';

const STORAGE_KEY = 'golem-workspace-layout';
const MIN_PANEL_SIZE = 10;
const MAX_PANEL_SIZE = 80;

export interface WorkspaceLayoutSnapshot {
  sidebarSize: number;
  chatSize: number;
  terminalSize: number;
  isChatVisible: boolean;
  isTerminalVisible: boolean;
}

export const DEFAULT_WORKSPACE_LAYOUT: WorkspaceLayoutSnapshot = {
  sidebarSize: 20,
  chatSize: 30,
  terminalSize: 30,
  isChatVisible: true,
  isTerminalVisible: false,
};

interface WorkspaceLayoutState extends WorkspaceLayoutSnapshot {
  setChatSize: (size: number) => void;
  setTerminalSize: (size: number) => void;
  setChatVisible: (visible: boolean) => void;
  setTerminalVisible: (visible: boolean) => void;
  toggleChat: () => void;
  toggleTerminal: () => void;
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
  };
}

const initialLayout = readInitialLayout();

export const useWorkspaceLayoutStore = create<WorkspaceLayoutState>((set, get) => ({
  ...initialLayout,

  setChatSize: (size: number): void => {
    const next = clampSize(size, DEFAULT_WORKSPACE_LAYOUT.chatSize);
    set({ chatSize: next });
    persistSnapshot(snapshotFromState(get()));
  },

  setTerminalSize: (size: number): void => {
    const next = clampSize(size, DEFAULT_WORKSPACE_LAYOUT.terminalSize);
    set({ terminalSize: next });
    persistSnapshot(snapshotFromState(get()));
  },

  setChatVisible: (visible: boolean): void => {
    set({ isChatVisible: visible });
    persistSnapshot(snapshotFromState(get()));
  },

  setTerminalVisible: (visible: boolean): void => {
    set({ isTerminalVisible: visible });
    persistSnapshot(snapshotFromState(get()));
  },

  toggleChat: (): void => {
    set({ isChatVisible: !get().isChatVisible });
    persistSnapshot(snapshotFromState(get()));
  },

  toggleTerminal: (): void => {
    set({ isTerminalVisible: !get().isTerminalVisible });
    persistSnapshot(snapshotFromState(get()));
  },
}));
