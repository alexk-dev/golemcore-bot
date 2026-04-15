import { useCallback, useEffect, useMemo, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import toast from 'react-hot-toast';
import {
  useCreateFileContent,
  useDeleteFilePath,
  useFileContent,
  useFileTree,
  useRenameFilePath,
  useSaveFileContent,
  useUploadFileContent,
} from './useFiles';
import { getFileTree, type FileTreeNode } from '../api/files';
import { mergeDirectoryChildren } from './ideTreeMerge';
import { useIdeCloseWorkflow } from './useIdeCloseWorkflow';
import { useIdeQuickOpen, type QuickOpenItem } from './useIdeQuickOpen';
import { useBeforeUnloadGuard, useGlobalIdeShortcuts, useSyncContentToTabs } from './useIdeLifecycle';
import { useIdeTreeActions, type TreeActionState } from './useIdeTreeActions';
import { useProtectedFileDownload } from './useProtectedFileDownload';
import { useResizableSidebar } from './useResizableSidebar';
import { type IdeTabState, useIdeStore } from '../store/ideStore';
import { buildIdeTabLabels, getFilename } from '../components/ide/ideTabLabels';

const SIDEBAR_WIDTH_CSS_VARIABLE = '--ide-sidebar-width';
const DEFAULT_TREE_DEPTH = 2;

export interface EditorSettingsState {
  fontSize: number;
  wordWrap: boolean;
  minimap: boolean;
  setMinimap: (value: boolean) => void;
}

export interface UseIdeWorkspaceResult {
  openedTabs: IdeTabState[];
  editorTabs: {
    path: string;
    title: string;
    context: string | null;
    fullTitle: string;
    dirty: boolean;
  }[];
  activePath: string | null;
  activeTab: IdeTabState | null;
  closeCandidate: IdeTabState | null;
  closeCandidateLabel: string;
  treeAction: TreeActionState | null;
  treeQuery: ReturnType<typeof useFileTree>;
  treeNodes: FileTreeNode[];
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
  isCommandPaletteVisible: boolean;
  isEditorSearchVisible: boolean;
  isEditorSettingsVisible: boolean;
  includeIgnored: boolean;
  quickOpenItems: QuickOpenItem[];
  activeLine: number;
  activeColumn: number;
  activeLanguage: string;
  activeFileSize: number;
  activeUpdatedAt: string | null;
  sidebarWidth: number;
  editorSearchQuery: string;
  editorSettings: EditorSettingsState;
  setActivePath: (path: string | null) => void;
  setTreeSearchQuery: (value: string) => void;
  setEditorSearchQuery: (value: string) => void;
  setEditorFontSize: (fontSize: number) => void;
  setEditorWordWrap: (wordWrap: boolean) => void;
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
  toggleCommandPalette: () => void;
  closeCommandPalette: () => void;
  toggleEditorSearch: () => void;
  toggleEditorSettings: () => void;
  toggleIncludeIgnored: () => void;
  loadDirectory: (path: string) => void;
  uploadFiles: (targetPath: string, files: FileList) => void;
  downloadActiveFile: () => void;
  isDownloadingActiveFile: boolean;
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

function buildEditorTabs(
  openedTabs: IdeTabState[],
  tabLabels: Map<string, { title: string; context: string | null; fullTitle: string }>,
): Array<{ path: string; title: string; context: string | null; fullTitle: string; dirty: boolean }> {
  return openedTabs.map((tab) => {
    const label = tabLabels.get(tab.path);
    return {
      path: tab.path,
      title: label?.title ?? tab.title,
      context: label?.context ?? null,
      fullTitle: label?.fullTitle ?? tab.title,
      dirty: tab.isDirty,
    };
  });
}

function buildDirtyPathSet(openedTabs: IdeTabState[]): Set<string> {
  return new Set(openedTabs.filter((tab) => tab.isDirty).map((tab) => tab.path));
}

function resolveActiveUpdatedAt(
  contentData: { path: string; updatedAt?: string | null } | undefined,
  activePath: string | undefined,
): string | null {
  if (contentData == null || contentData.path !== activePath) {
    return null;
  }

  return contentData.updatedAt ?? null;
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

function clampFontSize(fontSize: number): number {
  if (Number.isNaN(fontSize)) {
    return 14;
  }
  return Math.min(22, Math.max(11, fontSize));
}

export function useIdeWorkspace(): UseIdeWorkspaceResult {
  const [activeLine, setActiveLine] = useState<number>(1);
  const [activeColumn, setActiveColumn] = useState<number>(1);
  const [includeIgnored, setIncludeIgnored] = useState<boolean>(false);
  const [isCommandPaletteVisible, setCommandPaletteVisible] = useState<boolean>(false);
  const [isEditorSearchVisible, setEditorSearchVisible] = useState<boolean>(false);
  const [isEditorSettingsVisible, setEditorSettingsVisible] = useState<boolean>(false);
  const [editorSearchQuery, setEditorSearchQuery] = useState<string>('');
  const [editorFontSize, setEditorFontSizeState] = useState<number>(14);
  const [editorWordWrap, setEditorWordWrap] = useState<boolean>(true);
  const [editorMinimap, setEditorMinimap] = useState<boolean>(true);
  const [treeNodes, setTreeNodes] = useState<FileTreeNode[]>([]);
  const queryClient = useQueryClient();
  const activeFileDownload = useProtectedFileDownload();

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

  const treeQuery = useFileTree('', { depth: DEFAULT_TREE_DEPTH, includeIgnored });
  const contentQuery = useFileContent(activePath ?? '');
  const createMutation = useCreateFileContent();
  const saveMutation = useSaveFileContent();
  const uploadMutation = useUploadFileContent();
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
    treeNodes,
    setActivePath,
    recentPaths,
    pinnedPathList,
    togglePinnedPath,
  );

  const activeTab = useMemo(() => findTabByPath(openedTabs, activePath), [openedTabs, activePath]);
  const tabLabels = useMemo(() => buildIdeTabLabels(openedTabs.map((tab) => tab.path)), [openedTabs]);
  const editorTabs = useMemo(() => buildEditorTabs(openedTabs, tabLabels), [openedTabs, tabLabels]);
  const hasDirtyTabs = useMemo(() => openedTabs.some((tab) => tab.isDirty), [openedTabs]);
  const dirtyTabsCount = useMemo(() => openedTabs.filter((tab) => tab.isDirty).length, [openedTabs]);
  const dirtyPaths = useMemo(() => buildDirtyPathSet(openedTabs), [openedTabs]);
  const activeLanguage = resolveLanguage(activeTab?.path ?? null);
  const activeFileSize = activeTab == null ? 0 : new Blob([activeTab.content]).size;
  const activeUpdatedAt = useMemo(
    () => resolveActiveUpdatedAt(contentQuery.data, activeTab?.path),
    [activeTab?.path, contentQuery.data],
  );

  useEffect(() => {
    // Keep the editable tree model synchronized with the root lazy tree response.
    if (treeQuery.data != null) {
      setTreeNodes(treeQuery.data);
    }
  }, [treeQuery.data]);

  const saveTab = useCallback(async (tab: IdeTabState): Promise<boolean> => {
    if (!tab.editable) {
      toast.error(`${tab.path} cannot be edited inline`);
      return false;
    }

    try {
      const saved = await saveMutation.mutateAsync({ path: tab.path, content: tab.content });
      markSaved(saved.path, saved.content ?? '');
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
  const closeCandidateLabel = useMemo(() => {
    const closeCandidate = closeWorkflow.closeCandidate;
    if (closeCandidate == null) {
      return '';
    }

    return tabLabels.get(closeCandidate.path)?.fullTitle ?? closeCandidate.title;
  }, [closeWorkflow.closeCandidate, tabLabels]);

  const canSaveActiveTab = activeTab != null
    && activeTab.editable
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
    onCreateFile: async (path: string) => {
      const created = await createMutation.mutateAsync({ path, content: '' });
      return { path: created.path, content: created.content ?? '' };
    },
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
        mimeType: 'text/plain',
        binary: false,
        image: false,
        editable: true,
        downloadUrl: `/api/files/download?path=${encodeURIComponent(path)}`,
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
    if (activeTab?.editable !== true) {
      return;
    }
    updateTabContent(activeTab.path, nextValue);
  }, [activeTab, updateTabContent]);

  const setEditorCursor = useCallback((line: number, column: number): void => {
    setActiveLine(line);
    setActiveColumn(column);
  }, []);

  const toggleCommandPalette = useCallback((): void => {
    setCommandPaletteVisible((current) => !current);
  }, []);

  const closeCommandPalette = useCallback((): void => {
    setCommandPaletteVisible(false);
  }, []);

  const toggleEditorSearch = useCallback((): void => {
    setEditorSearchVisible((current) => !current);
  }, []);

  const toggleEditorSettings = useCallback((): void => {
    setEditorSettingsVisible((current) => !current);
  }, []);

  const toggleIncludeIgnored = useCallback((): void => {
    setIncludeIgnored((current) => !current);
  }, []);

  const loadDirectory = useCallback((path: string): void => {
    void (async () => {
      try {
        const children = await queryClient.fetchQuery({
          queryKey: ['files', 'tree', path, 1, includeIgnored],
          queryFn: () => getFileTree(path, { depth: 1, includeIgnored }),
        });
        setTreeNodes((current) => mergeDirectoryChildren(current, path, children));
      } catch {
        toast.error(`Failed to load ${path}`);
      }
    })();
  }, [includeIgnored, queryClient]);

  const uploadFiles = useCallback((targetPath: string, files: FileList): void => {
    Array.from(files).forEach((file) => {
      void (async () => {
        try {
          const uploaded = await uploadMutation.mutateAsync({ path: targetPath, file });
          upsertTab({
            path: uploaded.path,
            title: getFilename(uploaded.path),
            content: uploaded.content ?? '',
            savedContent: uploaded.content ?? '',
            isDirty: false,
            mimeType: uploaded.mimeType,
            binary: uploaded.binary,
            image: uploaded.image,
            editable: uploaded.editable,
            downloadUrl: uploaded.downloadUrl,
          });
          toast.success(`Uploaded ${uploaded.path}`);
        } catch {
          toast.error(`Failed to upload ${file.name}`);
        }
      })();
    });
  }, [uploadMutation, upsertTab]);

  const downloadActiveFile = useCallback((): void => {
    if (activePath == null) {
      return;
    }
    void activeFileDownload.downloadFile(activePath);
  }, [activeFileDownload, activePath]);

  const setEditorFontSize = useCallback((fontSize: number): void => {
    setEditorFontSizeState(clampFontSize(fontSize));
  }, []);

  useSyncContentToTabs({
    contentData: contentQuery.data,
    openedTabs,
    upsertTab,
  });

  useGlobalIdeShortcuts({
    onSave: saveActiveTab,
    onQuickOpen: quickOpen.openQuickOpen,
    onCommandPalette: toggleCommandPalette,
    onCloseActiveTab: closeWorkflow.requestCloseActiveTab,
    onActivatePreviousTab: activatePreviousTab,
    onActivateNextTab: activateNextTab,
  });

  useBeforeUnloadGuard(hasDirtyTabs);

  const isFileOpening = activePath != null && activeTab == null && contentQuery.isLoading;
  const hasFileLoadError = activePath != null && activeTab == null && contentQuery.isError;

  return {
    openedTabs,
    editorTabs,
    activePath,
    activeTab,
    closeCandidate: closeWorkflow.closeCandidate,
    closeCandidateLabel,
    treeAction: treeActions.treeAction,
    treeQuery,
    treeNodes,
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
    isCommandPaletteVisible,
    isEditorSearchVisible,
    isEditorSettingsVisible,
    includeIgnored,
    quickOpenItems: quickOpen.quickOpenItems,
    activeLine,
    activeColumn,
    activeLanguage,
    activeFileSize,
    activeUpdatedAt,
    sidebarWidth: sidebar.width,
    editorSearchQuery,
    editorSettings: {
      fontSize: editorFontSize,
      wordWrap: editorWordWrap,
      minimap: editorMinimap,
      setMinimap: setEditorMinimap,
    },
    setActivePath,
    setTreeSearchQuery: quickOpen.setTreeSearchQuery,
    setEditorSearchQuery,
    setEditorFontSize,
    setEditorWordWrap,
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
    toggleCommandPalette,
    closeCommandPalette,
    toggleEditorSearch,
    toggleEditorSettings,
    toggleIncludeIgnored,
    loadDirectory,
    uploadFiles,
    downloadActiveFile,
    isDownloadingActiveFile: activeFileDownload.isDownloading,
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
