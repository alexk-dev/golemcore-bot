import { type UseMutationResult, type UseQueryResult, useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { listPrompts, getPrompt, updatePrompt } from '../api/prompts';

export function usePrompts(): UseQueryResult<Awaited<ReturnType<typeof listPrompts>>, unknown> {
  return useQuery({ queryKey: ['prompts'], queryFn: listPrompts });
}

export function usePrompt(name: string): UseQueryResult<Awaited<ReturnType<typeof getPrompt>>, unknown> {
  return useQuery({
    queryKey: ['prompts', name],
    queryFn: () => getPrompt(name),
    enabled: name.length > 0,
  });
}

export function useUpdatePrompt(): UseMutationResult<
  Awaited<ReturnType<typeof updatePrompt>>,
  unknown,
  { name: string; section: Record<string, unknown> }
> {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ name, section }: { name: string; section: Record<string, unknown> }) =>
      updatePrompt(name, section),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['prompts'] }),
  });
}
