import { type UseMutationResult, type UseQueryResult, useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import {
  getSettings,
  updatePreferences,
  getModels,
  getRuntimeConfig,
  updateRuntimeConfig,
  updateModelRouterConfig,
  updateLlmConfig,
  addLlmProvider,
  addLlmProviderAndImport,
  updateLlmProvider,
  removeLlmProvider,
  testDraftLlmProvider,
  testSavedLlmProvider,
  updateToolsConfig,
  updateVoiceConfig,
  updateMemoryConfig,
  getMemoryPresets,
  updateSkillsConfig,
  updateTurnConfig,
  updateUsageConfig,
  updateTelemetryConfig,
  updateMcpConfig,
  addMcpCatalogEntry,
  updateMcpCatalogEntry,
  removeMcpCatalogEntry,
  updateHiveConfig,
  updatePlanConfig,
  updateAutoConfig,
  updateTracingConfig,
  updateAdvancedConfig,
} from '../api/settings';
import type {
  ModelRouterConfig,
  LlmConfig,
  LlmProviderConfig,
  LlmProviderImportResult,
  LlmProviderTestResult,
  ToolsConfig,
  VoiceConfig,
  RuntimeConfig,
  MemoryConfig,
  MemoryPreset,
  SkillsConfig,
  TurnConfig,
  UsageConfig,
  TelemetryConfig,
  McpConfig,
  McpCatalogEntry,
  HiveConfig,
  PlanConfig,
  AutoModeConfig,
  TracingConfig,
  RateLimitConfig,
  ResilienceConfig,
  SecurityConfig,
  CompactionConfig,
} from '../api/settingsTypes';

export function useSettings(): UseQueryResult<Awaited<ReturnType<typeof getSettings>>, unknown> {
  return useQuery({ queryKey: ['settings'], queryFn: getSettings });
}

export function useModels(): UseQueryResult<Awaited<ReturnType<typeof getModels>>, unknown> {
  return useQuery({ queryKey: ['settings', 'models'], queryFn: getModels });
}

export function useUpdatePreferences(): UseMutationResult<Awaited<ReturnType<typeof updatePreferences>>, unknown, Record<string, unknown>> {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: updatePreferences,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['settings'] }),
  });
}

// ==================== Runtime Config ====================

export function useRuntimeConfig(): UseQueryResult<Awaited<ReturnType<typeof getRuntimeConfig>>, unknown> {
  return useQuery({ queryKey: ['runtime-config'], queryFn: getRuntimeConfig });
}

export function useUpdateRuntimeConfig(): UseMutationResult<Awaited<ReturnType<typeof updateRuntimeConfig>>, unknown, RuntimeConfig> {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (config: RuntimeConfig) => updateRuntimeConfig(config),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['runtime-config'] }),
  });
}

export function useUpdateModelRouter(): UseMutationResult<Awaited<ReturnType<typeof updateModelRouterConfig>>, unknown, ModelRouterConfig> {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (config: ModelRouterConfig) => updateModelRouterConfig(config),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['runtime-config'] }),
  });
}

export function useUpdateLlm(): UseMutationResult<Awaited<ReturnType<typeof updateLlmConfig>>, unknown, LlmConfig> {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (config: LlmConfig) => updateLlmConfig(config),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['runtime-config'] }),
  });
}

export function useAddLlmProvider(): UseMutationResult<Awaited<ReturnType<typeof addLlmProvider>>, unknown, { name: string; config: LlmProviderConfig }> {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ name, config }: { name: string; config: LlmProviderConfig }) => addLlmProvider(name, config),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['runtime-config'] }),
  });
}

export function useAddLlmProviderAndImport(): UseMutationResult<
  LlmProviderImportResult,
  unknown,
  { name: string; config: LlmProviderConfig; selectedModelIds: string[] }
> {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ name, config, selectedModelIds }: { name: string; config: LlmProviderConfig; selectedModelIds: string[] }) =>
      addLlmProviderAndImport(name, config, selectedModelIds),
    onSuccess: () => Promise.all([
      qc.invalidateQueries({ queryKey: ['runtime-config'] }),
      qc.invalidateQueries({ queryKey: ['models-config'] }),
      qc.invalidateQueries({ queryKey: ['models-available'] }),
    ]),
  });
}

export function useUpdateLlmProvider(): UseMutationResult<Awaited<ReturnType<typeof updateLlmProvider>>, unknown, { name: string; config: LlmProviderConfig }> {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ name, config }: { name: string; config: LlmProviderConfig }) => updateLlmProvider(name, config),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['runtime-config'] }),
  });
}

export function useRemoveLlmProvider(): UseMutationResult<Awaited<ReturnType<typeof removeLlmProvider>>, unknown, string> {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (name: string) => removeLlmProvider(name),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['runtime-config'] }),
  });
}

export function useTestLlmProvider(): UseMutationResult<
  LlmProviderTestResult,
  unknown,
  | { mode: 'saved'; providerName: string }
  | { mode: 'draft'; providerName: string; config: LlmProviderConfig }
> {
  return useMutation({
    mutationFn: (request) => (
      request.mode === 'saved'
        ? testSavedLlmProvider(request.providerName)
        : testDraftLlmProvider(request.providerName, request.config)
    ),
  });
}

export function useUpdateTools(): UseMutationResult<Awaited<ReturnType<typeof updateToolsConfig>>, unknown, ToolsConfig> {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (config: ToolsConfig) => updateToolsConfig(config),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['runtime-config'] }),
  });
}

export function useUpdateVoice(): UseMutationResult<Awaited<ReturnType<typeof updateVoiceConfig>>, unknown, VoiceConfig> {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (config: VoiceConfig) => updateVoiceConfig(config),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['runtime-config'] }),
  });
}

export function useUpdateMemory(): UseMutationResult<Awaited<ReturnType<typeof updateMemoryConfig>>, unknown, MemoryConfig> {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (config: MemoryConfig) => updateMemoryConfig(config),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['runtime-config'] }),
  });
}

export function useMemoryPresets(): UseQueryResult<MemoryPreset[], unknown> {
  return useQuery({ queryKey: ['runtime-config', 'memory-presets'], queryFn: getMemoryPresets });
}

export function useUpdateSkills(): UseMutationResult<Awaited<ReturnType<typeof updateSkillsConfig>>, unknown, SkillsConfig> {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (config: SkillsConfig) => updateSkillsConfig(config),
    onSuccess: () => Promise.all([
      qc.invalidateQueries({ queryKey: ['runtime-config'] }),
      qc.invalidateQueries({ queryKey: ['skill-marketplace'] }),
    ]),
  });
}

export function useUpdateTurn(): UseMutationResult<Awaited<ReturnType<typeof updateTurnConfig>>, unknown, TurnConfig> {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (config: TurnConfig) => updateTurnConfig(config),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['runtime-config'] }),
  });
}

export function useUpdateUsage(): UseMutationResult<Awaited<ReturnType<typeof updateUsageConfig>>, unknown, UsageConfig> {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (config: UsageConfig) => updateUsageConfig(config),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['runtime-config'] }),
  });
}

export function useUpdateTelemetry(): UseMutationResult<Awaited<ReturnType<typeof updateTelemetryConfig>>, unknown, TelemetryConfig> {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (config: TelemetryConfig) => updateTelemetryConfig(config),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['runtime-config'] }),
  });
}

export function useUpdateMcp(): UseMutationResult<Awaited<ReturnType<typeof updateMcpConfig>>, unknown, McpConfig> {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (config: McpConfig) => updateMcpConfig(config),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['runtime-config'] }),
  });
}

export function useAddMcpCatalogEntry(): UseMutationResult<Awaited<ReturnType<typeof addMcpCatalogEntry>>, unknown, McpCatalogEntry> {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (entry: McpCatalogEntry) => addMcpCatalogEntry(entry),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['runtime-config'] }),
  });
}

export function useUpdateMcpCatalogEntry(): UseMutationResult<Awaited<ReturnType<typeof updateMcpCatalogEntry>>, unknown, { name: string; entry: McpCatalogEntry }> {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ name, entry }: { name: string; entry: McpCatalogEntry }) => updateMcpCatalogEntry(name, entry),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['runtime-config'] }),
  });
}

export function useRemoveMcpCatalogEntry(): UseMutationResult<Awaited<ReturnType<typeof removeMcpCatalogEntry>>, unknown, string> {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (name: string) => removeMcpCatalogEntry(name),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['runtime-config'] }),
  });
}

export function useUpdateHive(): UseMutationResult<Awaited<ReturnType<typeof updateHiveConfig>>, unknown, HiveConfig> {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (config: HiveConfig) => updateHiveConfig(config),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['runtime-config'] }),
  });
}

export function useUpdatePlan(): UseMutationResult<Awaited<ReturnType<typeof updatePlanConfig>>, unknown, PlanConfig> {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (config: PlanConfig) => updatePlanConfig(config),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['runtime-config'] }),
  });
}

export function useUpdateAuto(): UseMutationResult<Awaited<ReturnType<typeof updateAutoConfig>>, unknown, AutoModeConfig> {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (config: AutoModeConfig) => updateAutoConfig(config),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['runtime-config'] }),
  });
}

export function useUpdateTracing(): UseMutationResult<Awaited<ReturnType<typeof updateTracingConfig>>, unknown, TracingConfig> {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (config: TracingConfig) => updateTracingConfig(config),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['runtime-config'] }),
  });
}

export function useUpdateAdvanced(): UseMutationResult<
  Awaited<ReturnType<typeof updateAdvancedConfig>>,
  unknown,
  { rateLimit?: RateLimitConfig; security?: SecurityConfig; compaction?: CompactionConfig; resilience?: ResilienceConfig }
> {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (config: { rateLimit?: RateLimitConfig; security?: SecurityConfig; compaction?: CompactionConfig; resilience?: ResilienceConfig }) =>
      updateAdvancedConfig(config),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['runtime-config'] }),
  });
}
