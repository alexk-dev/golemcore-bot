import { useCallback, useMemo, useState } from 'react';
import type { FileTreeNode } from '../api/files';

export interface QuickOpenItem {
  path: string;
  title: string;
}

export interface UseIdeQuickOpenResult {
  searchQuery: string;
  isQuickOpenVisible: boolean;
  quickOpenItems: QuickOpenItem[];
  setSearchQuery: (value: string) => void;
  openQuickOpen: () => void;
  closeQuickOpen: () => void;
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
  const [searchQuery, setSearchQuery] = useState<string>('');
  const [isQuickOpenVisible, setIsQuickOpenVisible] = useState<boolean>(false);

  const quickOpenItems = useMemo(() => {
    const files = collectFileNodes(treeData ?? []);
    const lowered = searchQuery.trim().toLowerCase();
    if (lowered.length === 0) {
      return files.slice(0, 200);
    }

    return files
      .filter((file) => file.path.toLowerCase().includes(lowered) || file.title.toLowerCase().includes(lowered))
      .slice(0, 200);
  }, [searchQuery, treeData]);

  const openQuickOpen = useCallback((): void => {
    setIsQuickOpenVisible(true);
  }, []);

  const closeQuickOpen = useCallback((): void => {
    setIsQuickOpenVisible(false);
  }, []);

  const openFileFromQuickOpen = useCallback((path: string): void => {
    setActivePath(path);
    setIsQuickOpenVisible(false);
  }, [setActivePath]);

  return {
    searchQuery,
    isQuickOpenVisible,
    quickOpenItems,
    setSearchQuery,
    openQuickOpen,
    closeQuickOpen,
    openFileFromQuickOpen,
  };
}
