import { useCallback, useMemo, useState } from 'react';
import toast from 'react-hot-toast';

export type TreeActionMode = 'create' | 'rename' | 'delete';

export interface TreeActionState {
  mode: TreeActionMode;
  targetPath: string;
  defaultValue: string;
}

interface CreatedFilePayload {
  path: string;
  content: string;
}

export interface UseIdeTreeActionsOptions {
  activePath: string | null;
  isCreatePending: boolean;
  isRenamePending: boolean;
  isDeletePending: boolean;
  onCreateFile: (path: string) => Promise<CreatedFilePayload>;
  onRenamePath: (sourcePath: string, targetPath: string) => Promise<void>;
  onDeletePath: (targetPath: string) => Promise<void>;
  onTabCreated: (path: string, content: string) => void;
  onPathRenamed: (sourcePath: string, targetPath: string) => void;
  onPathDeleted: (targetPath: string) => void;
}

export interface UseIdeTreeActionsResult {
  treeAction: TreeActionState | null;
  isTreeActionPending: boolean;
  requestCreateFromTree: (targetPath: string) => void;
  requestRenameFromTree: (targetPath: string) => void;
  requestDeleteFromTree: (targetPath: string) => void;
  cancelTreeAction: () => void;
  submitCreateFromTree: (targetPath: string, nextPath: string) => void;
  submitRenameFromTree: (sourcePath: string, targetPath: string) => void;
  submitDeleteFromTree: (targetPath: string) => void;
}

function getFilename(path: string): string {
  const segments = path.split('/').filter((segment) => segment.length > 0);
  return segments[segments.length - 1] ?? path;
}

function getParentPath(path: string): string {
  const segments = path.split('/').filter((segment) => segment.length > 0);
  if (segments.length <= 1) {
    return '';
  }

  return segments.slice(0, -1).join('/');
}

function buildTargetPath(basePath: string, entryName: string): string {
  const normalizedEntry = entryName.trim().replace(/^\/+/, '');
  if (normalizedEntry.length === 0) {
    return '';
  }

  if (basePath.length === 0) {
    return normalizedEntry;
  }

  return `${basePath}/${normalizedEntry}`;
}

export function useIdeTreeActions({
  activePath,
  isCreatePending,
  isRenamePending,
  isDeletePending,
  onCreateFile,
  onRenamePath,
  onDeletePath,
  onTabCreated,
  onPathRenamed,
  onPathDeleted,
}: UseIdeTreeActionsOptions): UseIdeTreeActionsResult {
  const [treeAction, setTreeAction] = useState<TreeActionState | null>(null);

  const isTreeActionPending = useMemo(() => {
    return isCreatePending || isRenamePending || isDeletePending;
  }, [isCreatePending, isDeletePending, isRenamePending]);

  const requestCreateFromTree = useCallback((targetPath: string): void => {
    setTreeAction({
      mode: 'create',
      targetPath,
      defaultValue: '',
    });
  }, []);

  const requestRenameFromTree = useCallback((targetPath: string): void => {
    setTreeAction({
      mode: 'rename',
      targetPath,
      defaultValue: getFilename(targetPath),
    });
  }, []);

  const requestDeleteFromTree = useCallback((targetPath: string): void => {
    setTreeAction({
      mode: 'delete',
      targetPath,
      defaultValue: '',
    });
  }, []);

  const cancelTreeAction = useCallback((): void => {
    setTreeAction(null);
  }, []);

  const submitCreateFromTree = useCallback((targetPath: string, nextPath: string): void => {
    const parentPath = targetPath.length > 0 ? targetPath : getParentPath(activePath ?? '');
    const normalizedPath = buildTargetPath(parentPath, nextPath);

    if (normalizedPath.length === 0) {
      toast.error('File name is required');
      return;
    }

    void (async () => {
      try {
        const created = await onCreateFile(normalizedPath);
        onTabCreated(created.path, created.content);
        toast.success(`Created ${created.path}`);
        setTreeAction(null);
      } catch {
        toast.error(`Failed to create ${normalizedPath}`);
      }
    })();
  }, [activePath, onCreateFile, onTabCreated]);

  const submitRenameFromTree = useCallback((sourcePath: string, targetPath: string): void => {
    const parentPath = getParentPath(sourcePath);
    const normalizedTarget = buildTargetPath(parentPath, targetPath);

    if (normalizedTarget.length === 0) {
      toast.error('New name is required');
      return;
    }

    void (async () => {
      try {
        await onRenamePath(sourcePath, normalizedTarget);
        onPathRenamed(sourcePath, normalizedTarget);
        toast.success(`Renamed to ${normalizedTarget}`);
        setTreeAction(null);
      } catch {
        toast.error(`Failed to rename ${sourcePath}`);
      }
    })();
  }, [onPathRenamed, onRenamePath]);

  const submitDeleteFromTree = useCallback((targetPath: string): void => {
    void (async () => {
      try {
        await onDeletePath(targetPath);
        onPathDeleted(targetPath);
        toast.success(`Deleted ${targetPath}`);
        setTreeAction(null);
      } catch {
        toast.error(`Failed to delete ${targetPath}`);
      }
    })();
  }, [onDeletePath, onPathDeleted]);

  return {
    treeAction,
    isTreeActionPending,
    requestCreateFromTree,
    requestRenameFromTree,
    requestDeleteFromTree,
    cancelTreeAction,
    submitCreateFromTree,
    submitRenameFromTree,
    submitDeleteFromTree,
  };
}
