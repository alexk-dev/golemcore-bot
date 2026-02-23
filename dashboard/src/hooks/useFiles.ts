import {
  type UseMutationResult,
  type UseQueryResult,
  useMutation,
  useQuery,
  useQueryClient,
} from '@tanstack/react-query';
import { getFileContent, getFileTree, saveFileContent, type FileContent, type FileTreeNode } from '../api/files';

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
