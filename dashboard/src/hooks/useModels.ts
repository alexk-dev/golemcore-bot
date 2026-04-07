import { type UseMutationResult, type UseQueryResult, useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import {
  type ModelSettings,
  type ResolveModelRegistryRequest,
  type TestModelResponse,
  discoverProviderModels,
  getModelsConfig,
  getAvailableModels,
  resolveModelRegistryDefaults,
  saveModel,
  deleteModel,
  reloadModels,
  testModel,
} from '../api/models';

export function useModelsConfig(): UseQueryResult<Awaited<ReturnType<typeof getModelsConfig>>, unknown> {
  return useQuery({ queryKey: ['models-config'], queryFn: getModelsConfig });
}

export function useAvailableModels(): UseQueryResult<Awaited<ReturnType<typeof getAvailableModels>>, unknown> {
  return useQuery({ queryKey: ['models-available'], queryFn: getAvailableModels });
}

export function useDiscoveredProviderModels(
  providerName: string,
  enabled = true,
): UseQueryResult<Awaited<ReturnType<typeof discoverProviderModels>>, unknown> {
  return useQuery({
    queryKey: ['models-discover', providerName],
    queryFn: () => discoverProviderModels(providerName),
    enabled: enabled && providerName.trim().length > 0,
  });
}

export function useSaveModel(): UseMutationResult<
  Awaited<ReturnType<typeof saveModel>>,
  unknown,
  { id: string; previousId: string | null; settings: ModelSettings }
> {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (
      { id, previousId, settings }: { id: string; previousId: string | null; settings: ModelSettings },
    ) => saveModel(id, settings, previousId),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ['models-config'] });
      void qc.invalidateQueries({ queryKey: ['models-available'] });
    },
  });
}

export function useResolveModelRegistry(): UseMutationResult<
  Awaited<ReturnType<typeof resolveModelRegistryDefaults>>,
  unknown,
  ResolveModelRegistryRequest
> {
  return useMutation({
    mutationFn: (request: ResolveModelRegistryRequest) => resolveModelRegistryDefaults(request),
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

export function useTestModel(): UseMutationResult<TestModelResponse, unknown, string> {
  return useMutation({
    mutationFn: (model: string) => testModel(model),
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
