import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { listPrompts, getPrompt, updatePrompt, createPrompt, deletePrompt } from '../api/prompts';

export function usePrompts() {
  return useQuery({ queryKey: ['prompts'], queryFn: listPrompts });
}

export function usePrompt(name: string) {
  return useQuery({
    queryKey: ['prompts', name],
    queryFn: () => getPrompt(name),
    enabled: !!name,
  });
}

export function useUpdatePrompt() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ name, section }: { name: string; section: Record<string, unknown> }) =>
      updatePrompt(name, section),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['prompts'] }),
  });
}

export function useCreatePrompt() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: createPrompt,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['prompts'] }),
  });
}

export function useDeletePrompt() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: deletePrompt,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['prompts'] }),
  });
}
