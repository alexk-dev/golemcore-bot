import { create } from 'zustand';

const RECENT_STORAGE_KEY = 'ide.quick-open.recent';
const PINNED_STORAGE_KEY = 'ide.quick-open.pinned';
const MAX_RECENT_PATHS = 80;

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
  recentPaths: string[];
  pinnedPaths: string[];
  setActivePath: (path: string | null) => void;
  upsertTab: (tab: IdeTabState) => void;
  updateTabContent: (path: string, content: string) => void;
  markSaved: (path: string, content: string) => void;
  closeTab: (path: string) => void;
  closeTabsByPrefix: (prefixPath: string) => void;
  renamePathReferences: (sourcePath: string, targetPath: string) => void;
  activatePreviousTab: () => void;
  activateNextTab: () => void;
  markFileOpened: (path: string) => void;
  togglePinnedPath: (path: string) => void;
}

function getFilename(path: string): string {
  const parts = path.split('/');
  return parts[parts.length - 1] ?? path;
}

function isPathAffected(path: string, targetPath: string): boolean {
  return path === targetPath || path.startsWith(`${targetPath}/`);
}

function remapPath(path: string, sourcePath: string, targetPath: string): string {
  if (path === sourcePath) {
    return targetPath;
  }

  if (!path.startsWith(`${sourcePath}/`)) {
    return path;
  }

  const suffix = path.slice(sourcePath.length);
  return `${targetPath}${suffix}`;
}

function dedupePreserveOrder(paths: string[]): string[] {
  const unique = new Set<string>();
  const ordered: string[] = [];

  paths.forEach((path) => {
    if (unique.has(path)) {
      return;
    }
    unique.add(path);
    ordered.push(path);
  });

  return ordered;
}

function readStoredPaths(storageKey: string): string[] {
  if (typeof window === 'undefined') {
    return [];
  }

  try {
    const raw = window.localStorage.getItem(storageKey);
    if (raw == null) {
      return [];
    }

    const parsed: unknown = JSON.parse(raw);
    if (!Array.isArray(parsed)) {
      return [];
    }

    const stringValues = parsed.filter((item): item is string => typeof item === 'string' && item.length > 0);
    return dedupePreserveOrder(stringValues);
  } catch {
    return [];
  }
}

function writeStoredPaths(storageKey: string, paths: string[]): void {
  if (typeof window === 'undefined') {
    return;
  }

  try {
    window.localStorage.setItem(storageKey, JSON.stringify(paths));
  } catch {
    // Intentionally ignored: localStorage can be unavailable in private mode.
  }
}

function buildNextRecentPaths(currentRecentPaths: string[], path: string): string[] {
  const nextRecentPaths = [path, ...currentRecentPaths.filter((candidate) => candidate !== path)];
  return nextRecentPaths.slice(0, MAX_RECENT_PATHS);
}

function updateActiveByDirection(
  openedTabs: IdeTabState[],
  activePath: string | null,
  direction: 'previous' | 'next',
): string | null {
  if (openedTabs.length === 0) {
    return null;
  }

  const activeIndex = activePath == null
    ? -1
    : openedTabs.findIndex((tab) => tab.path === activePath);

  if (activeIndex === -1) {
    return openedTabs[0]?.path ?? null;
  }

  const delta = direction === 'next' ? 1 : -1;
  const nextIndex = (activeIndex + delta + openedTabs.length) % openedTabs.length;
  return openedTabs[nextIndex]?.path ?? null;
}

const initialRecentPaths = readStoredPaths(RECENT_STORAGE_KEY);
const initialPinnedPaths = readStoredPaths(PINNED_STORAGE_KEY);

export const useIdeStore = create<IdeStoreState>((set, get) => ({
  openedTabs: [],
  activePath: null,
  recentPaths: initialRecentPaths,
  pinnedPaths: initialPinnedPaths,

  setActivePath: (path: string | null): void => {
    set({ activePath: path });

    if (path != null) {
      get().markFileOpened(path);
    }
  },

  upsertTab: (tab: IdeTabState): void => {
    const currentTabs = get().openedTabs;
    const existing = currentTabs.find((candidate) => candidate.path === tab.path);

    if (existing == null) {
      set({
        openedTabs: [...currentTabs, tab],
        activePath: tab.path,
      });
      get().markFileOpened(tab.path);
      return;
    }

    set({
      openedTabs: currentTabs.map((candidate) => {
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

    get().markFileOpened(tab.path);
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

  closeTabsByPrefix: (prefixPath: string): void => {
    const currentTabs = get().openedTabs;
    const nextTabs = currentTabs.filter((tab) => !isPathAffected(tab.path, prefixPath));

    const currentActivePath = get().activePath;
    const activeRemoved = currentActivePath != null && isPathAffected(currentActivePath, prefixPath);
    const nextActivePath = activeRemoved
      ? (nextTabs[nextTabs.length - 1]?.path ?? null)
      : currentActivePath;

    const nextRecentPaths = get().recentPaths.filter((path) => !isPathAffected(path, prefixPath));
    const nextPinnedPaths = get().pinnedPaths.filter((path) => !isPathAffected(path, prefixPath));

    writeStoredPaths(RECENT_STORAGE_KEY, nextRecentPaths);
    writeStoredPaths(PINNED_STORAGE_KEY, nextPinnedPaths);

    set({
      openedTabs: nextTabs,
      activePath: nextActivePath,
      recentPaths: nextRecentPaths,
      pinnedPaths: nextPinnedPaths,
    });
  },

  renamePathReferences: (sourcePath: string, targetPath: string): void => {
    const nextTabs = get().openedTabs.map((tab) => {
      if (!isPathAffected(tab.path, sourcePath)) {
        return tab;
      }

      const nextPath = remapPath(tab.path, sourcePath, targetPath);
      return {
        ...tab,
        path: nextPath,
        title: getFilename(nextPath),
      };
    });

    const activePath = get().activePath;
    const nextActivePath = activePath != null && isPathAffected(activePath, sourcePath)
      ? remapPath(activePath, sourcePath, targetPath)
      : activePath;

    const nextRecentPaths = dedupePreserveOrder(get().recentPaths.map((path) => {
      if (!isPathAffected(path, sourcePath)) {
        return path;
      }
      return remapPath(path, sourcePath, targetPath);
    }));

    const nextPinnedPaths = dedupePreserveOrder(get().pinnedPaths.map((path) => {
      if (!isPathAffected(path, sourcePath)) {
        return path;
      }
      return remapPath(path, sourcePath, targetPath);
    }));

    writeStoredPaths(RECENT_STORAGE_KEY, nextRecentPaths);
    writeStoredPaths(PINNED_STORAGE_KEY, nextPinnedPaths);

    set({
      openedTabs: nextTabs,
      activePath: nextActivePath,
      recentPaths: nextRecentPaths,
      pinnedPaths: nextPinnedPaths,
    });
  },

  activatePreviousTab: (): void => {
    const nextActivePath = updateActiveByDirection(get().openedTabs, get().activePath, 'previous');
    if (nextActivePath == null) {
      return;
    }

    set({ activePath: nextActivePath });
    get().markFileOpened(nextActivePath);
  },

  activateNextTab: (): void => {
    const nextActivePath = updateActiveByDirection(get().openedTabs, get().activePath, 'next');
    if (nextActivePath == null) {
      return;
    }

    set({ activePath: nextActivePath });
    get().markFileOpened(nextActivePath);
  },

  markFileOpened: (path: string): void => {
    const nextRecentPaths = buildNextRecentPaths(get().recentPaths, path);
    writeStoredPaths(RECENT_STORAGE_KEY, nextRecentPaths);
    set({ recentPaths: nextRecentPaths });
  },

  togglePinnedPath: (path: string): void => {
    const currentPinnedPaths = get().pinnedPaths;
    const nextPinnedPaths = currentPinnedPaths.includes(path)
      ? currentPinnedPaths.filter((candidate) => candidate !== path)
      : [path, ...currentPinnedPaths.filter((candidate) => candidate !== path)];

    const deduped = dedupePreserveOrder(nextPinnedPaths);
    writeStoredPaths(PINNED_STORAGE_KEY, deduped);
    set({ pinnedPaths: deduped });
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
