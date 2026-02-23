import { useCallback, useMemo, useState } from 'react';
import toast from 'react-hot-toast';
import { useFileContent, useFileTree, useSaveFileContent } from './useFiles';
import { useIdeQuickOpen, type QuickOpenItem } from './useIdeQuickOpen';
import { useBeforeUnloadGuard, useGlobalIdeShortcuts, useSyncContentToTabs } from './useIdeLifecycle';
import { useResizableSidebar } from './useResizableSidebar';
import { type IdeTabState, useIdeStore } from '../store/ideStore';

const SIDEBAR_WIDTH_CSS_VARIABLE = '--ide-sidebar-width';

export interface UseIdeWorkspaceResult {
  openedTabs: IdeTabState[];
  activePath: string | null;
  activeTab: IdeTabState | null;
  closeCandidate: IdeTabState | null;
  treeQuery: ReturnType<typeof useFileTree>;
  contentQuery: ReturnType<typeof useFileContent>;
  saveMutation: ReturnType<typeof useSaveFileContent>;
  hasDirtyTabs: boolean;
  dirtyTabsCount: number;
  dirtyPaths: Set<string>;
  canSaveActiveTab: boolean;
  isCloseWithSavePending: boolean;
  isFileOpening: boolean;
  hasFileLoadError: boolean;
  searchQuery: string;
  isQuickOpenVisible: boolean;
  quickOpenItems: QuickOpenItem[];
  activeLine: number;
  activeColumn: number;
  sidebarWidth: number;
  setActivePath: (path: string | null) => void;
  setSearchQuery: (value: string) => void;
  refreshTree: () => void;
  saveActiveTab: () => void;
  requestCloseTab: (path: string) => void;
  cancelCloseCandidate: () => void;
  closeCandidateWithoutSaving: () => void;
  saveAndCloseCandidate: () => void;
  retryLoadContent: () => void;
  updateActiveTabContent: (nextValue: string) => void;
  openQuickOpen: () => void;
  closeQuickOpen: () => void;
  openFileFromQuickOpen: (path: string) => void;
  setEditorCursor: (line: number, column: number) => void;
  startSidebarResize: (clientX: number) => void;
  increaseSidebarWidth: () => void;
  decreaseSidebarWidth: () => void;
}

function findTabByPath(tabs: IdeTabState[], path: string | null): IdeTabState | null {
  if (path == null) {
    return null;
  }
  return tabs.find((tab) => tab.path === path) ?? null;
}

export function useIdeWorkspace(): UseIdeWorkspaceResult {
  const [closeCandidatePath, setCloseCandidatePath] = useState<string | null>(null);
  const [isCloseWithSavePending, setIsCloseWithSavePending] = useState<boolean>(false);
  const [activeLine, setActiveLine] = useState<number>(1);
  const [activeColumn, setActiveColumn] = useState<number>(1);

  const openedTabs = useIdeStore((state) => state.openedTabs);
  const activePath = useIdeStore((state) => state.activePath);
  const setActivePath = useIdeStore((state) => state.setActivePath);
  const upsertTab = useIdeStore((state) => state.upsertTab);
  const closeTab = useIdeStore((state) => state.closeTab);
  const updateTabContent = useIdeStore((state) => state.updateTabContent);
  const markSaved = useIdeStore((state) => state.markSaved);

  const treeQuery = useFileTree('');
  const contentQuery = useFileContent(activePath ?? '');
  const saveMutation = useSaveFileContent();

  const sidebar = useResizableSidebar({
    initialWidth: 320,
    minWidth: 240,
    maxWidth: 520,
    storageKey: 'ide.sidebar.width',
    cssVariableName: SIDEBAR_WIDTH_CSS_VARIABLE,
  });

  const quickOpen = useIdeQuickOpen(treeQuery.data, setActivePath);

  const activeTab = useMemo(() => findTabByPath(openedTabs, activePath), [openedTabs, activePath]);
  const closeCandidate = useMemo(() => findTabByPath(openedTabs, closeCandidatePath), [openedTabs, closeCandidatePath]);

  const hasDirtyTabs = useMemo(() => openedTabs.some((tab) => tab.isDirty), [openedTabs]);
  const dirtyTabsCount = useMemo(() => openedTabs.filter((tab) => tab.isDirty).length, [openedTabs]);
  const dirtyPaths = useMemo(() => {
    return new Set(openedTabs.filter((tab) => tab.isDirty).map((tab) => tab.path));
  }, [openedTabs]);

  const canSaveActiveTab = activeTab != null && activeTab.isDirty && !saveMutation.isPending && !isCloseWithSavePending;

  const saveTab = useCallback(async (tab: IdeTabState): Promise<boolean> => {
    try {
      const saved = await saveMutation.mutateAsync({ path: tab.path, content: tab.content });
      markSaved(saved.path, saved.content);
      toast.success(`Saved ${saved.path}`);
      return true;
    } catch {
      toast.error(`Failed to save ${tab.path}`);
      return false;
    }
  }, [markSaved, saveMutation]);

  const saveActiveTab = useCallback((): void => {
    if (activeTab == null || !canSaveActiveTab) {
      return;
    }
    void saveTab(activeTab);
  }, [activeTab, canSaveActiveTab, saveTab]);

  const requestCloseTab = useCallback((path: string): void => {
    const tab = findTabByPath(openedTabs, path);
    if (tab == null) {
      return;
    }

    if (tab.isDirty) {
      setCloseCandidatePath(path);
      return;
    }

    closeTab(path);
  }, [closeTab, openedTabs]);

  const cancelCloseCandidate = useCallback((): void => {
    setCloseCandidatePath(null);
  }, []);

  const closeCandidateWithoutSaving = useCallback((): void => {
    if (closeCandidatePath != null) {
      closeTab(closeCandidatePath);
    }
    setCloseCandidatePath(null);
  }, [closeCandidatePath, closeTab]);

  const saveAndCloseCandidate = useCallback((): void => {
    if (closeCandidate == null) {
      return;
    }

    void (async () => {
      setIsCloseWithSavePending(true);
      const isSaved = await saveTab(closeCandidate);
      setIsCloseWithSavePending(false);

      if (!isSaved) {
        return;
      }

      closeTab(closeCandidate.path);
      setCloseCandidatePath(null);
    })();
  }, [closeCandidate, closeTab, saveTab]);

  const refreshTree = useCallback((): void => {
    void treeQuery.refetch();
  }, [treeQuery]);

  const retryLoadContent = useCallback((): void => {
    void contentQuery.refetch();
  }, [contentQuery]);

  const updateActiveTabContent = useCallback((nextValue: string): void => {
    if (activeTab == null) {
      return;
    }
    updateTabContent(activeTab.path, nextValue);
  }, [activeTab, updateTabContent]);

  const setEditorCursor = useCallback((line: number, column: number): void => {
    setActiveLine(line);
    setActiveColumn(column);
  }, []);

  useSyncContentToTabs({
    contentData: contentQuery.data,
    openedTabs,
    upsertTab,
  });

  useGlobalIdeShortcuts({
    onSave: saveActiveTab,
    onQuickOpen: quickOpen.openQuickOpen,
  });

  useBeforeUnloadGuard(hasDirtyTabs);

  const isFileOpening = activePath != null && activeTab == null && contentQuery.isLoading;
  const hasFileLoadError = activePath != null && activeTab == null && contentQuery.isError;

  return {
    openedTabs,
    activePath,
    activeTab,
    closeCandidate,
    treeQuery,
    contentQuery,
    saveMutation,
    hasDirtyTabs,
    dirtyTabsCount,
    dirtyPaths,
    canSaveActiveTab,
    isCloseWithSavePending,
    isFileOpening,
    hasFileLoadError,
    searchQuery: quickOpen.searchQuery,
    isQuickOpenVisible: quickOpen.isQuickOpenVisible,
    quickOpenItems: quickOpen.quickOpenItems,
    activeLine,
    activeColumn,
    sidebarWidth: sidebar.width,
    setActivePath,
    setSearchQuery: quickOpen.setSearchQuery,
    refreshTree,
    saveActiveTab,
    requestCloseTab,
    cancelCloseCandidate,
    closeCandidateWithoutSaving,
    saveAndCloseCandidate,
    retryLoadContent,
    updateActiveTabContent,
    openQuickOpen: quickOpen.openQuickOpen,
    closeQuickOpen: quickOpen.closeQuickOpen,
    openFileFromQuickOpen: quickOpen.openFileFromQuickOpen,
    setEditorCursor,
    startSidebarResize: sidebar.startResize,
    increaseSidebarWidth: sidebar.increase,
    decreaseSidebarWidth: sidebar.decrease,
  };
}
