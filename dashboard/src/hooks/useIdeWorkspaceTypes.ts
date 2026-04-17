import type { UseMutationResult, UseQueryResult } from '@tanstack/react-query';
import type { FileContent, FileTreeNode } from '../api/files';
import type { IdeTabState } from '../store/ideStore';
import type { QuickOpenItem } from './useIdeQuickOpen';
import type { TreeActionState } from './useIdeTreeActions';

export interface EditorSettingsState {
  fontSize: number;
  wordWrap: boolean;
  minimap: boolean;
  setMinimap: (value: boolean) => void;
}

/**
 * Full view-model returned by the dashboard IDE workspace hook.
 */
export interface UseIdeWorkspaceResult {
  openedTabs: IdeTabState[];
  editorTabs: Array<{
    path: string;
    title: string;
    context: string | null;
    fullTitle: string;
    dirty: boolean;
  }>;
  activePath: string | null;
  activeTab: IdeTabState | null;
  closeCandidate: IdeTabState | null;
  closeCandidateLabel: string;
  treeAction: TreeActionState | null;
  treeQuery: UseQueryResult<FileTreeNode[], unknown>;
  treeNodes: FileTreeNode[];
  contentQuery: UseQueryResult<FileContent, unknown>;
  saveMutation: UseMutationResult<FileContent, unknown, { path: string; content: string }>;
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
