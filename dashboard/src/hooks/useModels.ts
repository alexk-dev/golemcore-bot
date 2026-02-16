import { type UseMutationResult, type UseQueryResult, useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import {
  type ModelSettings,
  getModelsConfig,
  getAvailableModels,
  saveModel,
  deleteModel,
  reloadModels,
} from '../api/models';

export function useModelsConfig(): UseQueryResult<Awaited<ReturnType<typeof getModelsConfig>>, unknown> {
  return useQuery({ queryKey: ['models-config'], queryFn: getModelsConfig });
}

export function useAvailableModels(): UseQueryResult<Awaited<ReturnType<typeof getAvailableModels>>, unknown> {
  return useQuery({ queryKey: ['models-available'], queryFn: getAvailableModels });
}

export function useSaveModel(): UseMutationResult<Awaited<ReturnType<typeof saveModel>>, unknown, { id: string; settings: ModelSettings }> {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, settings }: { id: string; settings: ModelSettings }) => saveModel(id, settings),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ['models-config'] });
      void qc.invalidateQueries({ queryKey: ['models-available'] });
    },
  });
}

export function useDeleteModel(): UseMutationResult<Awaited<ReturnType<typeof deleteModel>>, unknown, string> {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => deleteModel(id),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ['models-config'] });
      void qc.invalidateQueries({ queryKey: ['models-available'] });
    },
  });
}

export function useReloadModels(): UseMutationResult<Awaited<ReturnType<typeof reloadModels>>, unknown, void> {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: reloadModels,
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ['models-config'] });
      void qc.invalidateQueries({ queryKey: ['models-available'] });
    },
  });
}
