import { create } from 'zustand';

const STORAGE_KEY = 'gc.chat.composerCollapsed';

function loadInitial(): boolean {
  if (typeof window === 'undefined') {
    return false;
  }
  try {
    return window.localStorage.getItem(STORAGE_KEY) === '1';
  } catch {
    return false;
  }
}

function persist(value: boolean): void {
  if (typeof window === 'undefined') {
    return;
  }
  try {
    window.localStorage.setItem(STORAGE_KEY, value ? '1' : '0');
  } catch {
    // Ignore quota or privacy-mode failures; composer collapse state stays in memory.
  }
}

interface ChatUiState {
  composerCollapsed: boolean;
  toggleComposerCollapsed: () => void;
}

export const useChatUiStore = create<ChatUiState>((set) => ({
  composerCollapsed: loadInitial(),
  toggleComposerCollapsed: (): void => {
    set((state) => {
      const next = !state.composerCollapsed;
      persist(next);
      return { composerCollapsed: next };
    });
  },
}));
