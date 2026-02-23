import { useCallback, useMemo, useState } from 'react';
import type { FileTreeNode } from '../api/files';
import { useDebouncedValue } from './useDebouncedValue';

const MAX_RESULTS = 200;
const PIN_BONUS = 4000;
const RECENT_BONUS = 2000;

export interface QuickOpenItem {
  path: string;
  title: string;
  score: number;
  isPinned: boolean;
  isRecent: boolean;
}

export interface UseIdeQuickOpenResult {
  treeSearchQuery: string;
  debouncedTreeSearchQuery: string;
  quickOpenQuery: string;
  isQuickOpenVisible: boolean;
  quickOpenItems: QuickOpenItem[];
  setTreeSearchQuery: (value: string) => void;
  openQuickOpen: () => void;
  closeQuickOpen: () => void;
  updateQuickOpenQuery: (value: string) => void;
  openFileFromQuickOpen: (path: string) => void;
  toggleQuickOpenPinned: (path: string) => void;
}

interface ScoredFile {
  path: string;
  title: string;
  score: number;
  isPinned: boolean;
  isRecent: boolean;
}

function collectFileNodes(nodes: FileTreeNode[]): Array<{ path: string; title: string }> {
  return nodes.flatMap((node) => {
    if (node.type === 'file') {
      return [{ path: node.path, title: node.name }];
    }
    return collectFileNodes(node.children);
  });
}

function scoreByMatch(query: string, title: string, path: string): number {
  if (query.length === 0) {
    return 0;
  }

  const queryLowered = query.toLowerCase();
  const titleLowered = title.toLowerCase();
  const pathLowered = path.toLowerCase();

  if (pathLowered === queryLowered || titleLowered === queryLowered) {
    return 1600;
  }

  if (titleLowered.startsWith(queryLowered)) {
    return 1200;
  }

  if (pathLowered.startsWith(queryLowered)) {
    return 1050;
  }

  if (pathLowered.includes(`/${queryLowered}`)) {
    return 900;
  }

  if (titleLowered.includes(queryLowered)) {
    return 700;
  }

  if (pathLowered.includes(queryLowered)) {
    return 600;
  }

  if (isSubsequence(queryLowered, titleLowered)) {
    return 420;
  }

  if (isSubsequence(queryLowered, pathLowered)) {
    return 360;
  }

  return -1;
}

function isSubsequence(query: string, candidate: string): boolean {
  if (query.length === 0) {
    return true;
  }

  let queryIndex = 0;
  for (let index = 0; index < candidate.length; index += 1) {
    if (candidate[index] === query[queryIndex]) {
      queryIndex += 1;
      if (queryIndex === query.length) {
        return true;
      }
    }
  }

  return false;
}

function compareByScore(left: ScoredFile, right: ScoredFile): number {
  if (right.score !== left.score) {
    return right.score - left.score;
  }

  if (left.path.length !== right.path.length) {
    return left.path.length - right.path.length;
  }

  return left.path.localeCompare(right.path);
}

function applyRecencyBonus(path: string, recentPaths: string[]): number {
  const index = recentPaths.indexOf(path);
  if (index === -1) {
    return 0;
  }

  return RECENT_BONUS - Math.min(RECENT_BONUS, index * 8);
}

function applyPinBonus(path: string, pinnedPaths: string[]): number {
  const index = pinnedPaths.indexOf(path);
  if (index === -1) {
    return 0;
  }

  return PIN_BONUS - Math.min(PIN_BONUS, index * 12);
}

export function useIdeQuickOpen(
  treeData: FileTreeNode[] | undefined,
  setActivePath: (path: string | null) => void,
  recentPaths: string[],
  pinnedPaths: string[],
  togglePinnedPath: (path: string) => void,
): UseIdeQuickOpenResult {
  const [treeSearchQuery, setTreeSearchQuery] = useState<string>('');
  const [quickOpenQuery, setQuickOpenQuery] = useState<string>('');
  const [isQuickOpenVisible, setIsQuickOpenVisible] = useState<boolean>(false);

  const debouncedTreeSearchQuery = useDebouncedValue(treeSearchQuery, 120);
  const debouncedQuickOpenQuery = useDebouncedValue(quickOpenQuery, 120);

  const quickOpenItems = useMemo(() => {
    const files = collectFileNodes(treeData ?? []);
    const query = debouncedQuickOpenQuery.trim();

    const scored = files
      .map((file): ScoredFile | null => {
        const matchScore = scoreByMatch(query, file.title, file.path);
        if (query.length > 0 && matchScore < 0) {
          return null;
        }

        const pinBonus = applyPinBonus(file.path, pinnedPaths);
        const recentBonus = applyRecencyBonus(file.path, recentPaths);

        return {
          path: file.path,
          title: file.title,
          score: Math.max(0, matchScore) + pinBonus + recentBonus,
          isPinned: pinBonus > 0,
          isRecent: recentBonus > 0,
        };
      })
      .filter((item): item is ScoredFile => item != null)
      .sort(compareByScore)
      .slice(0, MAX_RESULTS);

    return scored;
  }, [debouncedQuickOpenQuery, pinnedPaths, recentPaths, treeData]);

  const openQuickOpen = useCallback((): void => {
    setQuickOpenQuery(treeSearchQuery);
    setIsQuickOpenVisible(true);
  }, [treeSearchQuery]);

  const closeQuickOpen = useCallback((): void => {
    setIsQuickOpenVisible(false);
  }, []);

  const updateQuickOpenQuery = useCallback((value: string): void => {
    setQuickOpenQuery(value);
  }, []);

  const openFileFromQuickOpen = useCallback((path: string): void => {
    setActivePath(path);
    setIsQuickOpenVisible(false);
  }, [setActivePath]);

  const toggleQuickOpenPinned = useCallback((path: string): void => {
    togglePinnedPath(path);
  }, [togglePinnedPath]);

  return {
    treeSearchQuery,
    debouncedTreeSearchQuery,
    quickOpenQuery,
    isQuickOpenVisible,
    quickOpenItems,
    setTreeSearchQuery,
    openQuickOpen,
    closeQuickOpen,
    updateQuickOpenQuery,
    openFileFromQuickOpen,
    toggleQuickOpenPinned,
  };
}
