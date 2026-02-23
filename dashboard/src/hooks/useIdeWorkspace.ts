import { useCallback, useEffect, useMemo, useState } from 'react';
import toast from 'react-hot-toast';
import { useFileContent, useFileTree, useSaveFileContent } from './useFiles';
import { createNewTab, type IdeTabState, useIdeStore } from '../store/ideStore';

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
  canSaveActiveTab: boolean;
  isCloseWithSavePending: boolean;
  isFileOpening: boolean;
  hasFileLoadError: boolean;
  setActivePath: (path: string | null) => void;
  refreshTree: () => void;
  saveActiveTab: () => void;
  requestCloseTab: (path: string) => void;
  cancelCloseCandidate: () => void;
  closeCandidateWithoutSaving: () => void;
  saveAndCloseCandidate: () => void;
  retryLoadContent: () => void;
  updateActiveTabContent: (nextValue: string) => void;
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

  const activeTab = useMemo(() => findTabByPath(openedTabs, activePath), [openedTabs, activePath]);
  const closeCandidate = useMemo(() => findTabByPath(openedTabs, closeCandidatePath), [openedTabs, closeCandidatePath]);

  const hasDirtyTabs = useMemo(() => openedTabs.some((tab) => tab.isDirty), [openedTabs]);
  const dirtyTabsCount = useMemo(() => openedTabs.filter((tab) => tab.isDirty).length, [openedTabs]);

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

  useEffect(() => {
    // Synchronize loaded API content into tab state when file becomes active.
    const payload = contentQuery.data;
    if (payload == null) {
      return;
    }

    const existing = findTabByPath(openedTabs, payload.path);
    if (existing == null) {
      upsertTab(createNewTab(payload.path, payload.content));
      return;
    }

    if (!existing.isDirty && existing.savedContent !== payload.content) {
      upsertTab({
        ...existing,
        content: payload.content,
        savedContent: payload.content,
        isDirty: false,
      });
    }
  }, [contentQuery.data, openedTabs, upsertTab]);

  useEffect(() => {
    // Register global save shortcut for editor workflow.
    const onKeyDown = (event: KeyboardEvent): void => {
      const isSaveCombo = (event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 's';
      if (!isSaveCombo) {
        return;
      }
      event.preventDefault();
      saveActiveTab();
    };

    window.addEventListener('keydown', onKeyDown);
    return () => {
      window.removeEventListener('keydown', onKeyDown);
    };
  }, [saveActiveTab]);

  useEffect(() => {
    // Warn before browser tab close/reload when user has unsaved changes.
    const onBeforeUnload = (event: BeforeUnloadEvent): void => {
      if (!hasDirtyTabs) {
        return;
      }
      event.preventDefault();
    };

    window.addEventListener('beforeunload', onBeforeUnload);
    return () => {
      window.removeEventListener('beforeunload', onBeforeUnload);
    };
  }, [hasDirtyTabs]);

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
    canSaveActiveTab,
    isCloseWithSavePending,
    isFileOpening,
    hasFileLoadError,
    setActivePath,
    refreshTree,
    saveActiveTab,
    requestCloseTab,
    cancelCloseCandidate,
    closeCandidateWithoutSaving,
    saveAndCloseCandidate,
    retryLoadContent,
    updateActiveTabContent,
  };
}
