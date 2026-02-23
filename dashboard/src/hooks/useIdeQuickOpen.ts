import { useCallback, useMemo, useState } from 'react';
import type { FileTreeNode } from '../api/files';
import { useDebouncedValue } from './useDebouncedValue';

export interface QuickOpenItem {
  path: string;
  title: string;
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
}

function collectFileNodes(nodes: FileTreeNode[]): QuickOpenItem[] {
  return nodes.flatMap((node) => {
    if (node.type === 'file') {
      return [{ path: node.path, title: node.name }];
    }
    return collectFileNodes(node.children);
  });
}

export function useIdeQuickOpen(
  treeData: FileTreeNode[] | undefined,
  setActivePath: (path: string | null) => void,
): UseIdeQuickOpenResult {
  const [treeSearchQuery, setTreeSearchQuery] = useState<string>('');
  const [quickOpenQuery, setQuickOpenQuery] = useState<string>('');
  const [isQuickOpenVisible, setIsQuickOpenVisible] = useState<boolean>(false);

  const debouncedTreeSearchQuery = useDebouncedValue(treeSearchQuery, 120);
  const debouncedQuickOpenQuery = useDebouncedValue(quickOpenQuery, 120);

  const quickOpenItems = useMemo(() => {
    const files = collectFileNodes(treeData ?? []);
    const lowered = debouncedQuickOpenQuery.trim().toLowerCase();
    if (lowered.length === 0) {
      return files.slice(0, 200);
    }

    return files
      .filter((file) => file.path.toLowerCase().includes(lowered) || file.title.toLowerCase().includes(lowered))
      .slice(0, 200);
  }, [debouncedQuickOpenQuery, treeData]);

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
  };
}
