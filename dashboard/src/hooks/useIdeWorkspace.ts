import { useCallback } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { getFilename } from '../components/ide/ideTabLabels';
import { useBeforeUnloadGuard, useGlobalIdeShortcuts, useSyncContentToTabs } from './useIdeLifecycle';
import { useIdeCloseWorkflow } from './useIdeCloseWorkflow';
import { useIdeInlineEdit } from './useIdeInlineEdit';
import { useIdeQuickOpen } from './useIdeQuickOpen';
import { useIdeTreeActions } from './useIdeTreeActions';
import { useProtectedFileDownload } from './useProtectedFileDownload';
import { useResizableSidebar } from './useResizableSidebar';
import { useIdeWorkspaceActions } from './useIdeWorkspaceActionSupport';
import {
  useIdeStoreBindings,
  useIdeWorkspaceDerived,
  useIdeWorkspaceViewState,
  useSaveTab,
  useSyncTreeNodes,
} from './useIdeWorkspaceStateSupport';
import type { UseIdeWorkspaceResult } from './useIdeWorkspaceTypes';
import {
  useCreateFileContent,
  useDeleteFilePath,
  useFileContent,
  useFileTree,
  useRenameFilePath,
  useSaveFileContent,
  useUploadFileContent,
} from './useFiles';

const SIDEBAR_WIDTH_CSS_VARIABLE = '--ide-sidebar-width';
const DEFAULT_TREE_DEPTH = 2;

interface BuildIdeWorkspaceResultOptions {
  store: ReturnType<typeof useIdeStoreBindings>;
  derived: ReturnType<typeof useIdeWorkspaceDerived>;
  treeActions: ReturnType<typeof useIdeTreeActions>;
  treeQuery: ReturnType<typeof useFileTree>;
  viewState: ReturnType<typeof useIdeWorkspaceViewState>['viewState'];
  quickOpen: ReturnType<typeof useIdeQuickOpen>;
  closeWorkflow: ReturnType<typeof useIdeCloseWorkflow>;
  actions: ReturnType<typeof useIdeWorkspaceActions>;
  inlineEdit: ReturnType<typeof useIdeInlineEdit>;
  sidebar: ReturnType<typeof useResizableSidebar>;
  activeFileDownload: ReturnType<typeof useProtectedFileDownload>;
  saveMutation: ReturnType<typeof useSaveFileContent>;
  contentQuery: ReturnType<typeof useFileContent>;
  saveActiveTab: () => void;
  setEditorSearchQuery: (value: string) => void;
  setEditorWordWrap: (wordWrap: boolean) => void;
  setEditorMinimap: (value: boolean) => void;
}

function buildIdeWorkspaceResult({
  store,
  derived,
  treeActions,
  treeQuery,
  viewState,
  quickOpen,
  closeWorkflow,
  actions,
  inlineEdit,
  sidebar,
  activeFileDownload,
  saveMutation,
  contentQuery,
  saveActiveTab,
  setEditorSearchQuery,
  setEditorWordWrap,
  setEditorMinimap,
}: BuildIdeWorkspaceResultOptions): UseIdeWorkspaceResult {
  return {
    openedTabs: store.openedTabs,
    editorTabs: derived.editorTabs,
    activePath: store.activePath,
    activeTab: derived.activeTab,
    closeCandidate: closeWorkflow.closeCandidate,
    closeCandidateLabel: derived.closeCandidateLabel,
    treeAction: treeActions.treeAction,
    treeQuery,
    treeNodes: viewState.treeNodes,
    contentQuery,
    saveMutation,
    hasDirtyTabs: derived.hasDirtyTabs,
    dirtyTabsCount: derived.dirtyTabsCount,
    dirtyPaths: derived.dirtyPaths,
    canSaveActiveTab: derived.canSaveActiveTab,
    canOpenInlineEdit: derived.activeTab?.editable === true && inlineEdit.currentSelection != null,
    currentInlineEditSelection: inlineEdit.currentSelection,
    isInlineEditVisible: inlineEdit.isInlineEditVisible,
    isSubmittingInlineEdit: inlineEdit.isSubmittingInlineEdit,
    isCloseWithSavePending: closeWorkflow.isCloseWithSavePending,
    isTreeActionPending: treeActions.isTreeActionPending,
    isFileOpening: derived.isFileOpening,
    hasFileLoadError: derived.hasFileLoadError,
    treeSearchQuery: quickOpen.treeSearchQuery,
    debouncedTreeSearchQuery: quickOpen.debouncedTreeSearchQuery,
    quickOpenQuery: quickOpen.quickOpenQuery,
    isQuickOpenVisible: quickOpen.isQuickOpenVisible,
    isCommandPaletteVisible: viewState.isCommandPaletteVisible,
    isEditorSearchVisible: viewState.isEditorSearchVisible,
    isEditorSettingsVisible: viewState.isEditorSettingsVisible,
    includeIgnored: viewState.includeIgnored,
    quickOpenItems: quickOpen.quickOpenItems,
    activeLine: viewState.activeLine,
    activeColumn: viewState.activeColumn,
    activeLanguage: derived.activeLanguage,
    activeFileSize: derived.activeFileSize,
    activeUpdatedAt: derived.activeUpdatedAt,
    sidebarWidth: sidebar.width,
    editorSearchQuery: viewState.editorSearchQuery,
    editorSettings: {
      fontSize: viewState.editorFontSize,
      wordWrap: viewState.editorWordWrap,
      minimap: viewState.editorMinimap,
      setMinimap: setEditorMinimap,
    },
    setActivePath: store.setActivePath,
    setTreeSearchQuery: quickOpen.setTreeSearchQuery,
    setEditorSearchQuery,
    setEditorFontSize: actions.setEditorFontSize,
    setEditorWordWrap,
    refreshTree: actions.refreshTree,
    saveActiveTab,
    openInlineEdit: inlineEdit.openInlineEdit,
    closeInlineEdit: inlineEdit.closeInlineEdit,
    submitInlineEdit: inlineEdit.submitInlineEdit,
    handleEditorSelectionChange: inlineEdit.handleEditorSelectionChange,
    requestCloseActiveTab: closeWorkflow.requestCloseActiveTab,
    requestCloseTab: closeWorkflow.requestCloseTab,
    cancelCloseCandidate: closeWorkflow.cancelCloseCandidate,
    closeCandidateWithoutSaving: closeWorkflow.closeCandidateWithoutSaving,
    saveAndCloseCandidate: closeWorkflow.saveAndCloseCandidate,
    retryLoadContent: actions.retryLoadContent,
    updateActiveTabContent: actions.updateActiveTabContent,
    openQuickOpen: quickOpen.openQuickOpen,
    closeQuickOpen: quickOpen.closeQuickOpen,
    updateQuickOpenQuery: quickOpen.updateQuickOpenQuery,
    openFileFromQuickOpen: quickOpen.openFileFromQuickOpen,
    toggleQuickOpenPinned: quickOpen.toggleQuickOpenPinned,
    toggleCommandPalette: actions.toggleCommandPalette,
    closeCommandPalette: actions.closeCommandPalette,
    toggleEditorSearch: actions.toggleEditorSearch,
    toggleEditorSettings: actions.toggleEditorSettings,
    toggleIncludeIgnored: actions.toggleIncludeIgnored,
    loadDirectory: actions.loadDirectory,
    uploadFiles: actions.uploadFiles,
    downloadActiveFile: actions.downloadActiveFile,
    isDownloadingActiveFile: activeFileDownload.isDownloading,
    setEditorCursor: actions.setEditorCursor,
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

/**
 * Aggregates IDE tree, editor, quick-open, and close-workflow state for the
 * workspace screen.
 */
export function useIdeWorkspace(): UseIdeWorkspaceResult {
  const queryClient = useQueryClient();
  const activeFileDownload = useProtectedFileDownload();
  const store = useIdeStoreBindings();
  const { viewState, setters } = useIdeWorkspaceViewState();

  const treeQuery = useFileTree('', { depth: DEFAULT_TREE_DEPTH, includeIgnored: viewState.includeIgnored });
  const contentQuery = useFileContent(store.activePath ?? '');
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
    viewState.treeNodes,
    store.setActivePath,
    store.recentPaths,
    store.pinnedPathList,
    store.togglePinnedPath,
  );

  const saveTab = useSaveTab(store.markSaved, saveMutation);
  const closeWorkflow = useIdeCloseWorkflow({
    openedTabs: store.openedTabs,
    activePath: store.activePath,
    closeTab: store.closeTab,
    saveTab,
  });

  const derived = useIdeWorkspaceDerived({
    openedTabs: store.openedTabs,
    activePath: store.activePath,
    contentData: contentQuery.data,
    contentQuery,
    closeCandidate: closeWorkflow.closeCandidate,
    isSavePending: saveMutation.isPending,
    isCloseWithSavePending: closeWorkflow.isCloseWithSavePending,
  });

  const inlineEdit = useIdeInlineEdit({ activeTab: derived.activeTab });

  useSyncTreeNodes(treeQuery.data, setters.setTreeNodes);

  const saveActiveTab = useCallback((): void => {
    if (derived.activeTab == null || !derived.canSaveActiveTab) {
      return;
    }
    void saveTab(derived.activeTab);
  }, [derived.activeTab, derived.canSaveActiveTab, saveTab]);

  const treeActions = useIdeTreeActions({
    activePath: store.activePath,
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
      store.upsertTab({
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
    onPathRenamed: store.renamePathReferences,
    onPathDeleted: store.closeTabsByPrefix,
  });

  const actions = useIdeWorkspaceActions({
    activePath: store.activePath,
    activeTab: derived.activeTab,
    includeIgnored: viewState.includeIgnored,
    queryClient,
    activeFileDownload,
    treeQuery,
    contentQuery,
    uploadMutation,
    setActiveLine: setters.setActiveLine,
    setActiveColumn: setters.setActiveColumn,
    setIncludeIgnored: setters.setIncludeIgnored,
    setCommandPaletteVisible: setters.setCommandPaletteVisible,
    setEditorSearchVisible: setters.setEditorSearchVisible,
    setEditorSettingsVisible: setters.setEditorSettingsVisible,
    setTreeNodes: setters.setTreeNodes,
    setEditorFontSizeState: setters.setEditorFontSizeState,
    updateTabContent: store.updateTabContent,
    upsertTab: store.upsertTab,
  });

  useSyncContentToTabs({
    contentData: contentQuery.data,
    openedTabs: store.openedTabs,
    upsertTab: store.upsertTab,
  });

  useGlobalIdeShortcuts({
    onSave: saveActiveTab,
    onQuickOpen: quickOpen.openQuickOpen,
    onCommandPalette: actions.toggleCommandPalette,
    onCloseActiveTab: closeWorkflow.requestCloseActiveTab,
    onActivatePreviousTab: store.activatePreviousTab,
    onActivateNextTab: store.activateNextTab,
    onInlineEdit: inlineEdit.openInlineEdit,
  });

  useBeforeUnloadGuard(derived.hasDirtyTabs);

  return buildIdeWorkspaceResult({
    store,
    derived,
    treeActions,
    treeQuery,
    viewState,
    quickOpen,
    closeWorkflow,
    actions,
    inlineEdit,
    sidebar,
    activeFileDownload,
    saveMutation,
    contentQuery,
    saveActiveTab,
    setEditorSearchQuery: setters.setEditorSearchQuery,
    setEditorWordWrap: setters.setEditorWordWrap,
    setEditorMinimap: setters.setEditorMinimap,
  });
}
