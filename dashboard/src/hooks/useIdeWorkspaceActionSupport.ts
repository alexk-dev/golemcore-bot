import { useCallback } from 'react';
import type { QueryClient, UseMutationResult, UseQueryResult } from '@tanstack/react-query';
import toast from 'react-hot-toast';
import { getFileTree, type FileContent, type FileTreeNode } from '../api/files';
import { getFilename } from '../components/ide/ideTabLabels';
import type { IdeTabState } from '../store/ideStore';
import type { ProtectedFileDownloadState } from './useProtectedFileDownload';
import { mergeDirectoryChildren } from './ideTreeMerge';

interface UseIdeWorkspaceActionsOptions {
  activePath: string | null;
  activeTab: IdeTabState | null;
  includeIgnored: boolean;
  queryClient: QueryClient;
  activeFileDownload: ProtectedFileDownloadState;
  treeQuery: UseQueryResult<FileTreeNode[], unknown>;
  contentQuery: UseQueryResult<FileContent, unknown>;
  uploadMutation: UseMutationResult<FileContent, unknown, { path: string; file: File }>;
  setActiveLine: React.Dispatch<React.SetStateAction<number>>;
  setActiveColumn: React.Dispatch<React.SetStateAction<number>>;
  setIncludeIgnored: React.Dispatch<React.SetStateAction<boolean>>;
  setCommandPaletteVisible: React.Dispatch<React.SetStateAction<boolean>>;
  setEditorSearchVisible: React.Dispatch<React.SetStateAction<boolean>>;
  setEditorSettingsVisible: React.Dispatch<React.SetStateAction<boolean>>;
  setTreeNodes: React.Dispatch<React.SetStateAction<FileTreeNode[]>>;
  setEditorFontSizeState: React.Dispatch<React.SetStateAction<number>>;
  updateTabContent: (path: string, content: string) => void;
  upsertTab: (tab: IdeTabState) => void;
}

export interface UseIdeWorkspaceActionsResult {
  refreshTree: () => void;
  retryLoadContent: () => void;
  updateActiveTabContent: (nextValue: string) => void;
  setEditorCursor: (line: number, column: number) => void;
  toggleCommandPalette: () => void;
  closeCommandPalette: () => void;
  toggleEditorSearch: () => void;
  toggleEditorSettings: () => void;
  toggleIncludeIgnored: () => void;
  loadDirectory: (path: string) => void;
  uploadFiles: (targetPath: string, files: FileList) => void;
  downloadActiveFile: () => void;
  setEditorFontSize: (fontSize: number) => void;
}

const DEFAULT_EDITOR_FONT_SIZE = 14;

function clampFontSize(fontSize: number): number {
  if (Number.isNaN(fontSize)) {
    return DEFAULT_EDITOR_FONT_SIZE;
  }
  return Math.min(22, Math.max(11, fontSize));
}

/**
 * Creates editor, tree, and download callbacks consumed by the workspace page.
 */
export function useIdeWorkspaceActions({
  activePath,
  activeTab,
  includeIgnored,
  queryClient,
  activeFileDownload,
  treeQuery,
  contentQuery,
  uploadMutation,
  setActiveLine,
  setActiveColumn,
  setIncludeIgnored,
  setCommandPaletteVisible,
  setEditorSearchVisible,
  setEditorSettingsVisible,
  setTreeNodes,
  setEditorFontSizeState,
  updateTabContent,
  upsertTab,
}: UseIdeWorkspaceActionsOptions): UseIdeWorkspaceActionsResult {
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
  }, [setActiveColumn, setActiveLine]);

  const toggleCommandPalette = useCallback((): void => {
    setCommandPaletteVisible((current) => !current);
  }, [setCommandPaletteVisible]);

  const closeCommandPalette = useCallback((): void => {
    setCommandPaletteVisible(false);
  }, [setCommandPaletteVisible]);

  const toggleEditorSearch = useCallback((): void => {
    setEditorSearchVisible((current) => !current);
  }, [setEditorSearchVisible]);

  const toggleEditorSettings = useCallback((): void => {
    setEditorSettingsVisible((current) => !current);
  }, [setEditorSettingsVisible]);

  const toggleIncludeIgnored = useCallback((): void => {
    setIncludeIgnored((current) => !current);
  }, [setIncludeIgnored]);

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
  }, [includeIgnored, queryClient, setTreeNodes]);

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
  }, [setEditorFontSizeState]);

  return {
    refreshTree,
    retryLoadContent,
    updateActiveTabContent,
    setEditorCursor,
    toggleCommandPalette,
    closeCommandPalette,
    toggleEditorSearch,
    toggleEditorSettings,
    toggleIncludeIgnored,
    loadDirectory,
    uploadFiles,
    downloadActiveFile,
    setEditorFontSize,
  };
}
