import { create } from 'zustand';
import { subscribeWithSelector } from 'zustand/middleware';
import { createUuid } from '../utils/uuid';

export type TerminalConnectionStatus = 'idle' | 'connecting' | 'connected' | 'disconnected' | 'error';

export interface TerminalTab {
  id: string;
  title: string;
}

interface TerminalState {
  tabs: TerminalTab[];
  activeTabId: string | null;
  connectionStatus: TerminalConnectionStatus;
  pendingInput: Record<string, string[]>;
  openTab: () => string;
  closeTab: (id: string) => void;
  setActiveTab: (id: string) => void;
  setConnectionStatus: (status: TerminalConnectionStatus) => void;
  enqueueInput: (tabId: string, chunk: string) => void;
  consumePendingInput: (tabId: string) => string[];
}

function nextTitle(tabs: TerminalTab[]): string {
  const usedNumbers = new Set<number>();
  for (const tab of tabs) {
    const match = /^Terminal (\d+)$/.exec(tab.title);
    if (match) {
      usedNumbers.add(Number.parseInt(match[1], 10));
    }
  }
  let candidate = 1;
  while (usedNumbers.has(candidate)) {
    candidate += 1;
  }
  return `Terminal ${candidate}`;
}

export const useTerminalStore = create<TerminalState>()(subscribeWithSelector((set, get) => ({
  tabs: [],
  activeTabId: null,
  connectionStatus: 'idle',
  pendingInput: {},

  openTab: (): string => {
    const currentTabs = get().tabs;
    const id = createUuid();
    const title = nextTitle(currentTabs);
    const nextTab: TerminalTab = { id, title };
    set({
      tabs: [...currentTabs, nextTab],
      activeTabId: id,
    });
    return id;
  },

  closeTab: (id: string): void => {
    const currentTabs = get().tabs;
    const index = currentTabs.findIndex((tab) => tab.id === id);
    if (index < 0) {
      return;
    }
    const nextTabs = currentTabs.filter((tab) => tab.id !== id);
    const wasActive = get().activeTabId === id;
    let nextActive: string | null = get().activeTabId;
    if (wasActive) {
      if (nextTabs.length === 0) {
        nextActive = null;
      } else {
        const fallbackIndex = Math.max(0, index - 1);
        nextActive = nextTabs[fallbackIndex]?.id ?? nextTabs[0]?.id ?? null;
      }
    }
    const { [id]: _discarded, ...remainingPending } = get().pendingInput;
    set({ tabs: nextTabs, activeTabId: nextActive, pendingInput: remainingPending });
  },

  setActiveTab: (id: string): void => {
    if (!get().tabs.some((tab) => tab.id === id)) {
      return;
    }
    set({ activeTabId: id });
  },

  setConnectionStatus: (status: TerminalConnectionStatus): void => {
    set({ connectionStatus: status });
  },

  enqueueInput: (tabId: string, chunk: string): void => {
    const current = get().pendingInput[tabId] ?? [];
    set({ pendingInput: { ...get().pendingInput, [tabId]: [...current, chunk] } });
  },

  consumePendingInput: (tabId: string): string[] => {
    const current = get().pendingInput[tabId] ?? [];
    if (current.length === 0) {
      return [];
    }
    const { [tabId]: _drained, ...rest } = get().pendingInput;
    set({ pendingInput: rest });
    return current;
  },
})));
