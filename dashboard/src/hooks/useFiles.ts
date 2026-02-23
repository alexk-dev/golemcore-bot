import {
  type UseMutationResult,
  type UseQueryResult,
  useMutation,
  useQuery,
  useQueryClient,
} from '@tanstack/react-query';
import {
  createFileContent,
  deleteFilePath,
  getFileContent,
  getFileTree,
  renameFilePath,
  saveFileContent,
  type FileContent,
  type FileRenameResponse,
  type FileTreeNode,
} from '../api/files';

export function useFileTree(path: string): UseQueryResult<FileTreeNode[], unknown> {
  return useQuery({
    queryKey: ['files', 'tree', path],
    queryFn: () => getFileTree(path),
  });
}

export function useFileContent(path: string): UseQueryResult<FileContent, unknown> {
  return useQuery({
    queryKey: ['files', 'content', path],
    queryFn: () => getFileContent(path),
    enabled: path.length > 0,
  });
}

export function useCreateFileContent(): UseMutationResult<FileContent, unknown, { path: string; content: string }> {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ path, content }: { path: string; content: string }) => createFileContent(path, content),
    onSuccess: (created: FileContent) => {
      queryClient.setQueryData<FileContent>(['files', 'content', created.path], created);
      void queryClient.invalidateQueries({ queryKey: ['files', 'tree'], exact: false });
    },
  });
}

export function useSaveFileContent(): UseMutationResult<FileContent, unknown, { path: string; content: string }> {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ path, content }: { path: string; content: string }) => saveFileContent(path, content),
    onSuccess: (saved: FileContent) => {
      queryClient.setQueryData<FileContent>(['files', 'content', saved.path], saved);
      void queryClient.invalidateQueries({ queryKey: ['files', 'tree'], exact: false });
    },
  });
}

export function useRenameFilePath(): UseMutationResult<FileRenameResponse, unknown, { sourcePath: string; targetPath: string }> {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ sourcePath, targetPath }: { sourcePath: string; targetPath: string }) => renameFilePath(sourcePath, targetPath),
    onSuccess: (renamed: FileRenameResponse) => {
      const previous = queryClient.getQueryData<FileContent>(['files', 'content', renamed.sourcePath]);
      if (previous != null) {
        queryClient.setQueryData<FileContent>(['files', 'content', renamed.targetPath], {
          ...previous,
          path: renamed.targetPath,
        });
        queryClient.removeQueries({ queryKey: ['files', 'content', renamed.sourcePath], exact: true });
      }
      void queryClient.invalidateQueries({ queryKey: ['files', 'tree'], exact: false });
    },
  });
}

export function useDeleteFilePath(): UseMutationResult<void, unknown, { path: string }> {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ path }: { path: string }) => deleteFilePath(path),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['files', 'tree'], exact: false });
    },
  });
}
