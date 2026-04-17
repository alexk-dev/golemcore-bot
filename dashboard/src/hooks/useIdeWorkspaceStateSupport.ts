import { useCallback, useEffect, useMemo, useState } from 'react';
import type { UseMutationResult, UseQueryResult } from '@tanstack/react-query';
import toast from 'react-hot-toast';
import type { FileContent, FileTreeNode } from '../api/files';
import { buildIdeTabLabels } from '../components/ide/ideTabLabels';
import { type IdeTabState, useIdeStore } from '../store/ideStore';
import type { UseIdeWorkspaceResult } from './useIdeWorkspaceTypes';

const DEFAULT_EDITOR_FONT_SIZE = 14;

interface IdeStoreBindings {
  openedTabs: IdeTabState[];
  activePath: string | null;
  recentPaths: string[];
  pinnedPathList: string[];
  setActivePath: (path: string | null) => void;
  upsertTab: (tab: IdeTabState) => void;
  closeTab: (path: string) => void;
  closeTabsByPrefix: (prefixPath: string) => void;
  renamePathReferences: (sourcePath: string, targetPath: string) => void;
  updateTabContent: (path: string, content: string) => void;
  markSaved: (path: string, content: string) => void;
  togglePinnedPath: (path: string) => void;
  activatePreviousTab: () => void;
  activateNextTab: () => void;
}

interface IdeWorkspaceViewState {
  activeLine: number;
  activeColumn: number;
  includeIgnored: boolean;
  isCommandPaletteVisible: boolean;
  isEditorSearchVisible: boolean;
  isEditorSettingsVisible: boolean;
  editorSearchQuery: string;
  editorFontSize: number;
  editorWordWrap: boolean;
  editorMinimap: boolean;
  treeNodes: FileTreeNode[];
}

interface IdeWorkspaceViewStateSetters {
  setActiveLine: React.Dispatch<React.SetStateAction<number>>;
  setActiveColumn: React.Dispatch<React.SetStateAction<number>>;
  setIncludeIgnored: React.Dispatch<React.SetStateAction<boolean>>;
  setCommandPaletteVisible: React.Dispatch<React.SetStateAction<boolean>>;
  setEditorSearchVisible: React.Dispatch<React.SetStateAction<boolean>>;
  setEditorSettingsVisible: React.Dispatch<React.SetStateAction<boolean>>;
  setEditorSearchQuery: React.Dispatch<React.SetStateAction<string>>;
  setEditorFontSizeState: React.Dispatch<React.SetStateAction<number>>;
  setEditorWordWrap: React.Dispatch<React.SetStateAction<boolean>>;
  setEditorMinimap: React.Dispatch<React.SetStateAction<boolean>>;
  setTreeNodes: React.Dispatch<React.SetStateAction<FileTreeNode[]>>;
}

interface UseIdeWorkspaceDerivedOptions {
  openedTabs: IdeTabState[];
  activePath: string | null;
  contentData: FileContent | undefined;
  contentQuery: UseQueryResult<FileContent, unknown>;
  closeCandidate: IdeTabState | null;
  isSavePending: boolean;
  isCloseWithSavePending: boolean;
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
): UseIdeWorkspaceResult['editorTabs'] {
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

function resolveActiveUpdatedAt(contentData: FileContent | undefined, activePath: string | null): string | null {
  if (contentData?.path !== activePath) {
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

/**
 * Reads all zustand-backed IDE tab and navigation bindings used by the workspace.
 */
export function useIdeStoreBindings(): IdeStoreBindings {
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

  return {
    openedTabs,
    activePath,
    recentPaths,
    pinnedPathList,
    setActivePath,
    upsertTab,
    closeTab,
    closeTabsByPrefix,
    renamePathReferences,
    updateTabContent,
    markSaved,
    togglePinnedPath,
    activatePreviousTab,
    activateNextTab,
  };
}

/**
 * Owns transient IDE view state such as search visibility, editor settings, and
 * the expandable tree model mirrored from the backend.
 */
export function useIdeWorkspaceViewState(): {
  viewState: IdeWorkspaceViewState;
  setters: IdeWorkspaceViewStateSetters;
} {
  const [activeLine, setActiveLine] = useState<number>(1);
  const [activeColumn, setActiveColumn] = useState<number>(1);
  const [includeIgnored, setIncludeIgnored] = useState<boolean>(false);
  const [isCommandPaletteVisible, setCommandPaletteVisible] = useState<boolean>(false);
  const [isEditorSearchVisible, setEditorSearchVisible] = useState<boolean>(false);
  const [isEditorSettingsVisible, setEditorSettingsVisible] = useState<boolean>(false);
  const [editorSearchQuery, setEditorSearchQuery] = useState<string>('');
  const [editorFontSize, setEditorFontSizeState] = useState<number>(DEFAULT_EDITOR_FONT_SIZE);
  const [editorWordWrap, setEditorWordWrap] = useState<boolean>(true);
  const [editorMinimap, setEditorMinimap] = useState<boolean>(true);
  const [treeNodes, setTreeNodes] = useState<FileTreeNode[]>([]);

  return {
    viewState: {
      activeLine,
      activeColumn,
      includeIgnored,
      isCommandPaletteVisible,
      isEditorSearchVisible,
      isEditorSettingsVisible,
      editorSearchQuery,
      editorFontSize,
      editorWordWrap,
      editorMinimap,
      treeNodes,
    },
    setters: {
      setActiveLine,
      setActiveColumn,
      setIncludeIgnored,
      setCommandPaletteVisible,
      setEditorSearchVisible,
      setEditorSettingsVisible,
      setEditorSearchQuery,
      setEditorFontSizeState,
      setEditorWordWrap,
      setEditorMinimap,
      setTreeNodes,
    },
  };
}

/**
 * Computes derived IDE metadata such as tab labels, dirty state, file language,
 * and close-candidate presentation.
 */
export function useIdeWorkspaceDerived({
  openedTabs,
  activePath,
  contentData,
  contentQuery,
  closeCandidate,
  isSavePending,
  isCloseWithSavePending,
}: UseIdeWorkspaceDerivedOptions): {
  activeTab: IdeTabState | null;
  editorTabs: UseIdeWorkspaceResult['editorTabs'];
  hasDirtyTabs: boolean;
  dirtyTabsCount: number;
  dirtyPaths: Set<string>;
  activeLanguage: string;
  activeFileSize: number;
  activeUpdatedAt: string | null;
  closeCandidateLabel: string;
  canSaveActiveTab: boolean;
  isFileOpening: boolean;
  hasFileLoadError: boolean;
} {
  const activeTab = useMemo(() => findTabByPath(openedTabs, activePath), [activePath, openedTabs]);
  const tabLabels = useMemo(() => buildIdeTabLabels(openedTabs.map((tab) => tab.path)), [openedTabs]);
  const editorTabs = useMemo(() => buildEditorTabs(openedTabs, tabLabels), [openedTabs, tabLabels]);
  const hasDirtyTabs = useMemo(() => openedTabs.some((tab) => tab.isDirty), [openedTabs]);
  const dirtyTabsCount = useMemo(() => openedTabs.filter((tab) => tab.isDirty).length, [openedTabs]);
  const dirtyPaths = useMemo(() => buildDirtyPathSet(openedTabs), [openedTabs]);
  const activeLanguage = resolveLanguage(activeTab?.path ?? null);
  const activeFileSize = activeTab == null ? 0 : new Blob([activeTab.content]).size;
  const activeUpdatedAt = useMemo(() => resolveActiveUpdatedAt(contentData, activePath), [activePath, contentData]);
  const closeCandidateLabel = useMemo(() => {
    if (closeCandidate == null) {
      return '';
    }

    return tabLabels.get(closeCandidate.path)?.fullTitle ?? closeCandidate.title;
  }, [closeCandidate, tabLabels]);
  const canSaveActiveTab = activeTab != null
    && activeTab.editable
    && activeTab.isDirty
    && !isSavePending
    && !isCloseWithSavePending;
  const isFileOpening = activePath != null && activeTab == null && contentQuery.isLoading;
  const hasFileLoadError = activePath != null && activeTab == null && contentQuery.isError;

  return {
    activeTab,
    editorTabs,
    hasDirtyTabs,
    dirtyTabsCount,
    dirtyPaths,
    activeLanguage,
    activeFileSize,
    activeUpdatedAt,
    closeCandidateLabel,
    canSaveActiveTab,
    isFileOpening,
    hasFileLoadError,
  };
}

/**
 * Keeps the mutable tree model aligned with the root tree query so lazy-loaded
 * directory expansions start from the latest backend snapshot.
 */
export function useSyncTreeNodes(
  treeData: FileTreeNode[] | undefined,
  setTreeNodes: React.Dispatch<React.SetStateAction<FileTreeNode[]>>,
): void {
  useEffect(() => {
    // Keep the editable tree model synchronized with the root lazy tree response.
    if (treeData != null) {
      setTreeNodes(treeData);
    }
  }, [setTreeNodes, treeData]);
}

/**
 * Builds the asynchronous save operation shared by explicit saves and the
 * unsaved-tab close workflow.
 */
export function useSaveTab(
  markSaved: (path: string, content: string) => void,
  saveMutation: UseMutationResult<FileContent, unknown, { path: string; content: string }>,
): (tab: IdeTabState) => Promise<boolean> {
  return useCallback(async (tab: IdeTabState): Promise<boolean> => {
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
}
