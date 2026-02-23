import { create } from 'zustand';

export interface IdeTabState {
  path: string;
  title: string;
  content: string;
  savedContent: string;
  isDirty: boolean;
}

interface IdeStoreState {
  openedTabs: IdeTabState[];
  activePath: string | null;
  setActivePath: (path: string | null) => void;
  upsertTab: (tab: IdeTabState) => void;
  updateTabContent: (path: string, content: string) => void;
  markSaved: (path: string, content: string) => void;
  closeTab: (path: string) => void;
}

function getFilename(path: string): string {
  const parts = path.split('/');
  return parts[parts.length - 1] ?? path;
}

export const useIdeStore = create<IdeStoreState>((set, get) => ({
  openedTabs: [],
  activePath: null,

  setActivePath: (path: string | null): void => {
    set({ activePath: path });
  },

  upsertTab: (tab: IdeTabState): void => {
    const current = get().openedTabs;
    const existing = current.find((candidate) => candidate.path === tab.path);

    if (existing == null) {
      set({
        openedTabs: [...current, tab],
        activePath: tab.path,
      });
      return;
    }

    set({
      openedTabs: current.map((candidate) => {
        if (candidate.path !== tab.path) {
          return candidate;
        }
        return {
          ...candidate,
          ...tab,
        };
      }),
      activePath: tab.path,
    });
  },

  updateTabContent: (path: string, content: string): void => {
    set({
      openedTabs: get().openedTabs.map((tab) => {
        if (tab.path !== path) {
          return tab;
        }
        return {
          ...tab,
          content,
          isDirty: content !== tab.savedContent,
        };
      }),
    });
  },

  markSaved: (path: string, content: string): void => {
    set({
      openedTabs: get().openedTabs.map((tab) => {
        if (tab.path !== path) {
          return tab;
        }
        return {
          ...tab,
          content,
          savedContent: content,
          isDirty: false,
        };
      }),
    });
  },

  closeTab: (path: string): void => {
    const currentTabs = get().openedTabs;
    const index = currentTabs.findIndex((tab) => tab.path === path);
    if (index === -1) {
      return;
    }

    const nextTabs = currentTabs.filter((tab) => tab.path !== path);
    let nextActivePath = get().activePath;

    if (nextActivePath === path) {
      if (nextTabs.length === 0) {
        nextActivePath = null;
      } else {
        const fallbackIndex = Math.max(0, index - 1);
        nextActivePath = nextTabs[fallbackIndex]?.path ?? null;
      }
    }

    set({
      openedTabs: nextTabs,
      activePath: nextActivePath,
    });
  },
}));

export function createNewTab(path: string, content: string): IdeTabState {
  return {
    path,
    title: getFilename(path),
    content,
    savedContent: content,
    isDirty: false,
  };
}
