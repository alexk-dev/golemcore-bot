import {
  type PluginActionResult,
  type PluginInstallResult,
  type PluginMarketplaceCatalogResponse,
  type PluginSettingsCatalogItem,
  type PluginSettingsSection,
  type VoiceProvidersResponse,
  executePluginSettingsAction,
  getPluginMarketplace,
  getPluginSettingsCatalog,
  getPluginSettingsSection,
  getVoiceProviders,
  installPluginFromMarketplace,
  savePluginSettingsSection,
} from '../api/plugins';
import { type UseMutationResult, type UseQueryResult, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

function invalidatePluginQueries(queryClient: ReturnType<typeof useQueryClient>): Promise<unknown[]> {
  return Promise.all([
    queryClient.invalidateQueries({ queryKey: ['plugin-settings-catalog'] }),
    queryClient.invalidateQueries({ queryKey: ['plugin-marketplace'] }),
    queryClient.invalidateQueries({ queryKey: ['plugin-voice-providers'] }),
    queryClient.invalidateQueries({ queryKey: ['runtime-config'] }),
  ]);
}

export function usePluginSettingsCatalog(): UseQueryResult<PluginSettingsCatalogItem[], unknown> {
  return useQuery({
    queryKey: ['plugin-settings-catalog'],
    queryFn: getPluginSettingsCatalog,
  });
}

export function usePluginMarketplace(): UseQueryResult<PluginMarketplaceCatalogResponse, unknown> {
  return useQuery({
    queryKey: ['plugin-marketplace'],
    queryFn: getPluginMarketplace,
  });
}

export function usePluginSettingsSection(routeKey: string | null): UseQueryResult<PluginSettingsSection, unknown> {
  return useQuery({
    queryKey: ['plugin-settings-section', routeKey],
    queryFn: () => getPluginSettingsSection(routeKey ?? ''),
    enabled: routeKey != null && routeKey.length > 0,
  });
}

export function useSavePluginSettingsSection(
  routeKey: string,
): UseMutationResult<PluginSettingsSection, unknown, Record<string, unknown>> {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (values: Record<string, unknown>) => savePluginSettingsSection(routeKey, values),
    onSuccess: () => Promise.all([
      qc.invalidateQueries({ queryKey: ['plugin-settings-section', routeKey] }),
      invalidatePluginQueries(qc),
    ]),
  });
}

export function useExecutePluginSettingsAction(
  routeKey: string,
): UseMutationResult<PluginActionResult, unknown, { actionId: string; payload?: Record<string, unknown> }> {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ actionId, payload }: { actionId: string; payload?: Record<string, unknown> }) =>
      executePluginSettingsAction(routeKey, actionId, payload ?? {}),
    onSuccess: () => Promise.all([
      qc.invalidateQueries({ queryKey: ['plugin-settings-section', routeKey] }),
      invalidatePluginQueries(qc),
    ]),
  });
}

export function useVoiceProviders(): UseQueryResult<VoiceProvidersResponse, unknown> {
  return useQuery({
    queryKey: ['plugin-voice-providers'],
    queryFn: getVoiceProviders,
  });
}

export function useInstallPluginFromMarketplace(): UseMutationResult<
  PluginInstallResult,
  unknown,
  { pluginId: string; version?: string | null }
> {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ pluginId, version }: { pluginId: string; version?: string | null }) =>
      installPluginFromMarketplace(pluginId, version ?? null),
    onSuccess: () => invalidatePluginQueries(qc),
  });
}
