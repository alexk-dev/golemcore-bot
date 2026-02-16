import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import type {
  TelegramConfig,
  ModelRouterConfig,
  LlmConfig,
  ToolsConfig,
  VoiceConfig,
  MemoryConfig,
  SkillsConfig,
  TurnConfig,
  UsageConfig,
  RagConfig,
  McpConfig,
  AutoModeConfig,
  RateLimitConfig,
  SecurityConfig,
  CompactionConfig,
  WebhookConfig} from '../api/settings';
import {
  getSettings,
  updatePreferences,
  getModels,
  getRuntimeConfig,
  updateTelegramConfig,
  updateModelRouterConfig,
  updateLlmConfig,
  updateToolsConfig,
  updateVoiceConfig,
  updateMemoryConfig,
  updateSkillsConfig,
  updateTurnConfig,
  updateUsageConfig,
  updateRagConfig,
  updateMcpConfig,
  updateWebhooksConfig,
  updateAutoConfig,
  updateAdvancedConfig,
  generateInviteCode,
  deleteInviteCode,
  restartTelegram
} from '../api/settings';

export function useSettings() {
  return useQuery({ queryKey: ['settings'], queryFn: getSettings });
}

export function useModels() {
  return useQuery({ queryKey: ['settings', 'models'], queryFn: getModels });
}

export function useUpdatePreferences() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: updatePreferences,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['settings'] }),
  });
}

// ==================== Runtime Config ====================

export function useRuntimeConfig() {
  return useQuery({ queryKey: ['runtime-config'], queryFn: getRuntimeConfig });
}

export function useUpdateTelegram() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (config: TelegramConfig) => updateTelegramConfig(config),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['runtime-config'] }),
  });
}

export function useUpdateModelRouter() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (config: ModelRouterConfig) => updateModelRouterConfig(config),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['runtime-config'] }),
  });
}

export function useUpdateLlm() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (config: LlmConfig) => updateLlmConfig(config),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['runtime-config'] }),
  });
}

export function useUpdateTools() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (config: ToolsConfig) => updateToolsConfig(config),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['runtime-config'] }),
  });
}

export function useUpdateVoice() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (config: VoiceConfig) => updateVoiceConfig(config),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['runtime-config'] }),
  });
}

export function useUpdateMemory() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (config: MemoryConfig) => updateMemoryConfig(config),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['runtime-config'] }),
  });
}

export function useUpdateSkills() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (config: SkillsConfig) => updateSkillsConfig(config),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['runtime-config'] }),
  });
}

export function useUpdateTurn() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (config: TurnConfig) => updateTurnConfig(config),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['runtime-config'] }),
  });
}

export function useUpdateUsage() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (config: UsageConfig) => updateUsageConfig(config),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['runtime-config'] }),
  });
}

export function useUpdateRag() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (config: RagConfig) => updateRagConfig(config),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['runtime-config'] }),
  });
}

export function useUpdateMcp() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (config: McpConfig) => updateMcpConfig(config),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['runtime-config'] }),
  });
}

export function useUpdateWebhooks() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (config: WebhookConfig) => updateWebhooksConfig(config),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['runtime-config'] }),
  });
}

export function useUpdateAuto() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (config: AutoModeConfig) => updateAutoConfig(config),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['runtime-config'] }),
  });
}

export function useUpdateAdvanced() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (config: { rateLimit?: RateLimitConfig; security?: SecurityConfig; compaction?: CompactionConfig }) =>
      updateAdvancedConfig(config),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['runtime-config'] }),
  });
}

export function useGenerateInviteCode() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: () => generateInviteCode(),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['runtime-config'] }),
  });
}

export function useDeleteInviteCode() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (code: string) => deleteInviteCode(code),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['runtime-config'] }),
  });
}

export function useRestartTelegram() {
  return useMutation({ mutationFn: restartTelegram });
}
