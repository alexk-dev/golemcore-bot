import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import {
  getModelsConfig,
  getAvailableModels,
  saveModel,
  deleteModel,
  reloadModels,
  ModelSettings,
} from '../api/models';

export function useModelsConfig() {
  return useQuery({ queryKey: ['models-config'], queryFn: getModelsConfig });
}

export function useAvailableModels() {
  return useQuery({ queryKey: ['models-available'], queryFn: getAvailableModels });
}

export function useSaveModel() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, settings }: { id: string; settings: ModelSettings }) => saveModel(id, settings),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['models-config'] });
      qc.invalidateQueries({ queryKey: ['models-available'] });
    },
  });
}

export function useDeleteModel() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => deleteModel(id),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['models-config'] });
      qc.invalidateQueries({ queryKey: ['models-available'] });
    },
  });
}

export function useReloadModels() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: reloadModels,
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['models-config'] });
      qc.invalidateQueries({ queryKey: ['models-available'] });
    },
  });
}
