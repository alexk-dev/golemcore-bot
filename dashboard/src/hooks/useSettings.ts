import { type UseMutationResult, type UseQueryResult, useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import {
  type ModelRouterConfig,
  type LlmConfig,
  type LlmProviderConfig,
  type ToolsConfig,
  type VoiceConfig,
  type MemoryConfig,
  type MemoryPreset,
  type SkillsConfig,
  type TurnConfig,
  type UsageConfig,
  type McpConfig,
  type PlanConfig,
  type AutoModeConfig,
  type RateLimitConfig,
  type SecurityConfig,
  type CompactionConfig,
  getSettings,
  updatePreferences,
  getModels,
  getRuntimeConfig,
  updateModelRouterConfig,
  updateLlmConfig,
  addLlmProvider,
  updateLlmProvider,
  removeLlmProvider,
  updateToolsConfig,
  updateVoiceConfig,
  updateMemoryConfig,
  getMemoryPresets,
  updateSkillsConfig,
  updateTurnConfig,
  updateUsageConfig,
  updateMcpConfig,
  updatePlanConfig,
  updateAutoConfig,
  updateAdvancedConfig,
} from '../api/settings';

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

export function useUpdateMcp(): UseMutationResult<Awaited<ReturnType<typeof updateMcpConfig>>, unknown, McpConfig> {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (config: McpConfig) => updateMcpConfig(config),
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

export function useUpdateAdvanced(): UseMutationResult<
  Awaited<ReturnType<typeof updateAdvancedConfig>>,
  unknown,
  { rateLimit?: RateLimitConfig; security?: SecurityConfig; compaction?: CompactionConfig }
> {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (config: { rateLimit?: RateLimitConfig; security?: SecurityConfig; compaction?: CompactionConfig }) =>
      updateAdvancedConfig(config),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['runtime-config'] }),
  });
}
