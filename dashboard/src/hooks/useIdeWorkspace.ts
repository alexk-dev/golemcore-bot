import { useCallback, useMemo, useState } from 'react';
import toast from 'react-hot-toast';
import {
  useCreateFileContent,
  useDeleteFilePath,
  useFileContent,
  useFileTree,
  useRenameFilePath,
  useSaveFileContent,
} from './useFiles';
import { useIdeCloseWorkflow } from './useIdeCloseWorkflow';
import { useIdeQuickOpen, type QuickOpenItem } from './useIdeQuickOpen';
import { useBeforeUnloadGuard, useGlobalIdeShortcuts, useSyncContentToTabs } from './useIdeLifecycle';
import { useIdeTreeActions, type TreeActionState } from './useIdeTreeActions';
import { useResizableSidebar } from './useResizableSidebar';
import { type IdeTabState, useIdeStore } from '../store/ideStore';

const SIDEBAR_WIDTH_CSS_VARIABLE = '--ide-sidebar-width';

export interface UseIdeWorkspaceResult {
  openedTabs: IdeTabState[];
  activePath: string | null;
  activeTab: IdeTabState | null;
  closeCandidate: IdeTabState | null;
  treeAction: TreeActionState | null;
  treeQuery: ReturnType<typeof useFileTree>;
  contentQuery: ReturnType<typeof useFileContent>;
  saveMutation: ReturnType<typeof useSaveFileContent>;
  hasDirtyTabs: boolean;
  dirtyTabsCount: number;
  dirtyPaths: Set<string>;
  canSaveActiveTab: boolean;
  isCloseWithSavePending: boolean;
  isTreeActionPending: boolean;
  isFileOpening: boolean;
  hasFileLoadError: boolean;
  treeSearchQuery: string;
  debouncedTreeSearchQuery: string;
  quickOpenQuery: string;
  isQuickOpenVisible: boolean;
  quickOpenItems: QuickOpenItem[];
  activeLine: number;
  activeColumn: number;
  activeLanguage: string;
  activeFileSize: number;
  activeUpdatedAt: string | null;
  sidebarWidth: number;
  setActivePath: (path: string | null) => void;
  setTreeSearchQuery: (value: string) => void;
  refreshTree: () => void;
  saveActiveTab: () => void;
  requestCloseActiveTab: () => void;
  requestCloseTab: (path: string) => void;
  cancelCloseCandidate: () => void;
  closeCandidateWithoutSaving: () => void;
  saveAndCloseCandidate: () => void;
  retryLoadContent: () => void;
  updateActiveTabContent: (nextValue: string) => void;
  openQuickOpen: () => void;
  closeQuickOpen: () => void;
  updateQuickOpenQuery: (value: string) => void;
  openFileFromQuickOpen: (path: string) => void;
  toggleQuickOpenPinned: (path: string) => void;
  setEditorCursor: (line: number, column: number) => void;
  startSidebarResize: (clientX: number) => void;
  increaseSidebarWidth: () => void;
  decreaseSidebarWidth: () => void;
  requestCreateFromTree: (targetPath: string) => void;
  requestRenameFromTree: (targetPath: string) => void;
  requestDeleteFromTree: (targetPath: string) => void;
  cancelTreeAction: () => void;
  submitCreateFromTree: (targetPath: string, nextPath: string) => void;
  submitRenameFromTree: (sourcePath: string, targetPath: string) => void;
  submitDeleteFromTree: (targetPath: string) => void;
}

function findTabByPath(tabs: IdeTabState[], path: string | null): IdeTabState | null {
  if (path == null) {
    return null;
  }
  return tabs.find((tab) => tab.path === path) ?? null;
}

function resolveLanguage(filePath: string | null): string {
  if (filePath == null) {
    return 'plain';
  }

  const parts = filePath.split('/');
  const fileName = parts[parts.length - 1] ?? '';
  const extension = fileName.split('.').pop()?.toLowerCase() ?? '';

  const aliases: Record<string, string> = {
    java: 'java',
    ts: 'typescript',
    tsx: 'typescript',
    js: 'javascript',
    jsx: 'javascript',
    json: 'json',
    md: 'markdown',
    yml: 'yaml',
    yaml: 'yaml',
    xml: 'xml',
    html: 'html',
    css: 'css',
    scss: 'scss',
    sh: 'bash',
    py: 'python',
    go: 'go',
    rs: 'rust',
    kt: 'kotlin',
    sql: 'sql',
    toml: 'toml',
    ini: 'ini',
  };

  return aliases[extension] ?? (extension.length > 0 ? extension : 'plain');
}

function getFilename(path: string): string {
  const parts = path.split('/');
  return parts[parts.length - 1] ?? path;
}

export function useIdeWorkspace(): UseIdeWorkspaceResult {
  const [activeLine, setActiveLine] = useState<number>(1);
  const [activeColumn, setActiveColumn] = useState<number>(1);

  const openedTabs = useIdeStore((state) => state.openedTabs);
  const activePath = useIdeStore((state) => state.activePath);
  const recentPaths = useIdeStore((state) => state.recentPaths);
  const pinnedPathList = useIdeStore((state) => state.pinnedPaths);
  const setActivePath = useIdeStore((state) => state.setActivePath);
  const upsertTab = useIdeStore((state) => state.upsertTab);
  const closeTab = useIdeStore((state) => state.closeTab);
  const closeTabsByPrefix = useIdeStore((state) => state.closeTabsByPrefix);
  const renamePathReferences = useIdeStore((state) => state.renamePathReferences);
  const updateTabContent = useIdeStore((state) => state.updateTabContent);
  const markSaved = useIdeStore((state) => state.markSaved);
  const togglePinnedPath = useIdeStore((state) => state.togglePinnedPath);
  const activatePreviousTab = useIdeStore((state) => state.activatePreviousTab);
  const activateNextTab = useIdeStore((state) => state.activateNextTab);

  const treeQuery = useFileTree('');
  const contentQuery = useFileContent(activePath ?? '');
  const createMutation = useCreateFileContent();
  const saveMutation = useSaveFileContent();
  const renameMutation = useRenameFilePath();
  const deleteMutation = useDeleteFilePath();

  const sidebar = useResizableSidebar({
    initialWidth: 320,
    minWidth: 240,
    maxWidth: 520,
    storageKey: 'ide.sidebar.width',
    cssVariableName: SIDEBAR_WIDTH_CSS_VARIABLE,
  });

  const quickOpen = useIdeQuickOpen(
    treeQuery.data,
    setActivePath,
    recentPaths,
    pinnedPathList,
    togglePinnedPath,
  );

  const activeTab = useMemo(() => findTabByPath(openedTabs, activePath), [openedTabs, activePath]);

  const hasDirtyTabs = useMemo(() => openedTabs.some((tab) => tab.isDirty), [openedTabs]);
  const dirtyTabsCount = useMemo(() => openedTabs.filter((tab) => tab.isDirty).length, [openedTabs]);
  const dirtyPaths = useMemo(() => {
    return new Set(openedTabs.filter((tab) => tab.isDirty).map((tab) => tab.path));
  }, [openedTabs]);

  const activeLanguage = useMemo(() => resolveLanguage(activeTab?.path ?? null), [activeTab?.path]);
  const activeFileSize = useMemo(() => {
    if (activeTab == null) {
      return 0;
    }
    return new Blob([activeTab.content]).size;
  }, [activeTab]);

  const activeUpdatedAt = useMemo(() => {
    if (contentQuery.data?.path !== activeTab?.path) {
      return null;
    }

    return contentQuery.data?.updatedAt ?? null;
  }, [activeTab?.path, contentQuery.data]);

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

  const closeWorkflow = useIdeCloseWorkflow({
    openedTabs,
    activePath,
    closeTab,
    saveTab,
  });

  const canSaveActiveTab = activeTab != null
    && activeTab.isDirty
    && !saveMutation.isPending
    && !closeWorkflow.isCloseWithSavePending;

  const saveActiveTab = useCallback((): void => {
    if (activeTab == null || !canSaveActiveTab) {
      return;
    }
    void saveTab(activeTab);
  }, [activeTab, canSaveActiveTab, saveTab]);

  const treeActions = useIdeTreeActions({
    activePath,
    isCreatePending: createMutation.isPending,
    isRenamePending: renameMutation.isPending,
    isDeletePending: deleteMutation.isPending,
    onCreateFile: async (path: string) => createMutation.mutateAsync({ path, content: '' }),
    onRenamePath: async (sourcePath: string, targetPath: string) => {
      await renameMutation.mutateAsync({ sourcePath, targetPath });
    },
    onDeletePath: async (path: string) => {
      await deleteMutation.mutateAsync({ path });
    },
    onTabCreated: (path: string, content: string) => {
      upsertTab({
        path,
        title: getFilename(path),
        content,
        savedContent: content,
        isDirty: false,
      });
    },
    onPathRenamed: renamePathReferences,
    onPathDeleted: closeTabsByPrefix,
  });

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
    onCloseActiveTab: closeWorkflow.requestCloseActiveTab,
    onActivatePreviousTab: activatePreviousTab,
    onActivateNextTab: activateNextTab,
  });

  useBeforeUnloadGuard(hasDirtyTabs);

  const isFileOpening = activePath != null && activeTab == null && contentQuery.isLoading;
  const hasFileLoadError = activePath != null && activeTab == null && contentQuery.isError;

  return {
    openedTabs,
    activePath,
    activeTab,
    closeCandidate: closeWorkflow.closeCandidate,
    treeAction: treeActions.treeAction,
    treeQuery,
    contentQuery,
    saveMutation,
    hasDirtyTabs,
    dirtyTabsCount,
    dirtyPaths,
    canSaveActiveTab,
    isCloseWithSavePending: closeWorkflow.isCloseWithSavePending,
    isTreeActionPending: treeActions.isTreeActionPending,
    isFileOpening,
    hasFileLoadError,
    treeSearchQuery: quickOpen.treeSearchQuery,
    debouncedTreeSearchQuery: quickOpen.debouncedTreeSearchQuery,
    quickOpenQuery: quickOpen.quickOpenQuery,
    isQuickOpenVisible: quickOpen.isQuickOpenVisible,
    quickOpenItems: quickOpen.quickOpenItems,
    activeLine,
    activeColumn,
    activeLanguage,
    activeFileSize,
    activeUpdatedAt,
    sidebarWidth: sidebar.width,
    setActivePath,
    setTreeSearchQuery: quickOpen.setTreeSearchQuery,
    refreshTree,
    saveActiveTab,
    requestCloseActiveTab: closeWorkflow.requestCloseActiveTab,
    requestCloseTab: closeWorkflow.requestCloseTab,
    cancelCloseCandidate: closeWorkflow.cancelCloseCandidate,
    closeCandidateWithoutSaving: closeWorkflow.closeCandidateWithoutSaving,
    saveAndCloseCandidate: closeWorkflow.saveAndCloseCandidate,
    retryLoadContent,
    updateActiveTabContent,
    openQuickOpen: quickOpen.openQuickOpen,
    closeQuickOpen: quickOpen.closeQuickOpen,
    updateQuickOpenQuery: quickOpen.updateQuickOpenQuery,
    openFileFromQuickOpen: quickOpen.openFileFromQuickOpen,
    toggleQuickOpenPinned: quickOpen.toggleQuickOpenPinned,
    setEditorCursor,
    startSidebarResize: sidebar.startResize,
    increaseSidebarWidth: sidebar.increase,
    decreaseSidebarWidth: sidebar.decrease,
    requestCreateFromTree: treeActions.requestCreateFromTree,
    requestRenameFromTree: treeActions.requestRenameFromTree,
    requestDeleteFromTree: treeActions.requestDeleteFromTree,
    cancelTreeAction: treeActions.cancelTreeAction,
    submitCreateFromTree: treeActions.submitCreateFromTree,
    submitRenameFromTree: treeActions.submitRenameFromTree,
    submitDeleteFromTree: treeActions.submitDeleteFromTree,
  };
}
