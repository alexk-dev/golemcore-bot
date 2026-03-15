import { type UseMutationResult, type UseQueryResult, useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import {
  createPrompt,
  deletePrompt,
  getPrompt,
  listPrompts,
  previewPrompt,
  reorderPrompts,
  type PromptReorderPayload,
  type PromptSectionPayload,
  updatePrompt,
} from '../api/prompts';

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
  { name: string; section: PromptSectionPayload }
> {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ name, section }: { name: string; section: PromptSectionPayload }) =>
      updatePrompt(name, section),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['prompts'] }),
  });
}

export function useCreatePrompt(): UseMutationResult<
  Awaited<ReturnType<typeof createPrompt>>,
  unknown,
  { name: string; section: PromptSectionPayload }
> {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ name, section }: { name: string; section: PromptSectionPayload }) =>
      createPrompt(name, section),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['prompts'] }),
  });
}

export function useDeletePrompt(): UseMutationResult<Awaited<ReturnType<typeof deletePrompt>>, unknown, string> {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (name: string) => deletePrompt(name),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['prompts'] }),
  });
}

export function usePreviewPrompt(): UseMutationResult<
  Awaited<ReturnType<typeof previewPrompt>>,
  unknown,
  { name: string; section: PromptSectionPayload }
> {
  return useMutation({
    mutationFn: ({ name, section }: { name: string; section: PromptSectionPayload }) =>
      previewPrompt(name, section),
  });
}

export function useReorderPrompts(): UseMutationResult<
  Awaited<ReturnType<typeof reorderPrompts>>,
  unknown,
  PromptReorderPayload[]
> {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (entries: PromptReorderPayload[]) => reorderPrompts(entries),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['prompts'] }),
  });
}
