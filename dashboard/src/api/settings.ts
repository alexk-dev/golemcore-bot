import client, { type TelemetryRequestConfig } from './client';
import type {
  AutoModeConfig,
  CompactionConfig,
  HiveConfig,
  LlmConfig,
  LlmProviderConfig,
  LlmProviderImportResult,
  LlmProviderTestResult,
  McpCatalogEntry,
  McpConfig,
  MemoryConfig,
  MemoryPreset,
  ModelRouterConfig,
  PlanConfig,
  RateLimitConfig,
  ResilienceConfig,
  RuntimeConfig,
  SecurityConfig,
  SkillsConfig,
  TelemetryConfig,
  ToolsConfig,
  TracingConfig,
  TurnConfig,
  UsageConfig,
  VoiceConfig,
} from './settingsTypes';
import { normalizeLlmApiType, toSecretPayload } from './settingsApiUtils';
import { toUiRuntimeConfig, type RuntimeConfigUiRecord } from './settingsRuntimeMappers';
import type { UnknownRecord } from './settingsUtils';

export { modelReferenceFromSpec, modelReferenceToSpec } from './settingsModelMappers';
export { getRuntimeConfig, updateRuntimeConfig } from './settingsRuntimeConfig';

export type {
  ApiType,
  AutoModeConfig,
  CompactionConfig,
  HiveConfig,
  LlmConfig,
  LlmProviderConfig,
  LlmProviderImportResult,
  LlmProviderTestMode,
  LlmProviderTestResult,
  McpCatalogEntry,
  McpConfig,
  MemoryConfig,
  MemoryDisclosureConfig,
  MemoryDisclosureMode,
  MemoryDiagnosticsConfig,
  MemoryDiagnosticsVerbosity,
  MemoryPreset,
  MemoryPromptStyle,
  ModelRouterConfig,
  PlanConfig,
  RateLimitConfig,
  ResilienceConfig,
  RuntimeConfig,
  SecurityConfig,
  SelfEvolvingBenchmarkConfig,
  SelfEvolvingCaptureConfig,
  SelfEvolvingEvolutionConfig,
  SelfEvolvingHiveConfig,
  SelfEvolvingJudgeConfig,
  SelfEvolvingPromotionConfig,
  SelfEvolvingTacticBm25Config,
  SelfEvolvingTacticEmbeddingsConfig,
  SelfEvolvingTacticEmbeddingsLocalConfig,
  SelfEvolvingTacticQueryExpansionConfig,
  SelfEvolvingTacticSearchConfig,
  SelfEvolvingTacticsConfig,
  SelfEvolvingToggleConfig,
  SkillsConfig,
  TelemetryConfig,
  ToolsConfig,
  TracingConfig,
  TurnConfig,
  UsageConfig,
  VoiceConfig,
} from './settingsTypes';

function withSettingsSectionTelemetry(sectionKey: string): TelemetryRequestConfig {
  return {
    _telemetry: {
      counterKey: 'settings_save_count_by_section',
      value: sectionKey,
    },
  } as TelemetryRequestConfig;
}

export interface SettingsResponse extends Record<string, unknown> {
  language?: string;
  timezone?: string;
  modelTier?: string | null;
  tierForce?: boolean;
  memoryPreset?: string | null;
}

export async function getSettings(): Promise<SettingsResponse> {
  const { data } = await client.get<SettingsResponse>('/settings');
  return data;
}

export async function updatePreferences(prefs: Record<string, unknown>): Promise<SettingsResponse> {
  const { data } = await client.put<SettingsResponse>(
    '/settings/preferences',
    prefs,
    withSettingsSectionTelemetry('general'),
  );
  return data;
}

export async function getModels(): Promise<UnknownRecord> {
  const { data } = await client.get<UnknownRecord>('/settings/models');
  return data;
}

export async function updateTierOverrides(overrides: Record<string, { model: string; reasoning: string }>): Promise<UnknownRecord> {
  const { data } = await client.put<UnknownRecord>('/settings/tier-overrides', overrides);
  return data;
}

// ==================== Runtime Config ====================

export async function updateModelRouterConfig(config: ModelRouterConfig): Promise<RuntimeConfig> {
  const { data } = await client.put<RuntimeConfigUiRecord>(
    '/settings/runtime/models',
    config,
    withSettingsSectionTelemetry('models'),
  );
  return toUiRuntimeConfig(data);
}

export async function updateLlmConfig(config: LlmConfig): Promise<RuntimeConfig> {
  const payload = {
    ...config,
    providers: Object.fromEntries(
      Object.entries(config.providers ?? {}).map(([name, provider]) => [
        name,
        {
          baseUrl: provider.baseUrl,
          requestTimeoutSeconds: provider.requestTimeoutSeconds,
          apiKey: toSecretPayload(provider.apiKey ?? null),
          apiType: normalizeLlmApiType(provider.apiType),
          legacyApi: provider.legacyApi === true ? true : null,
        },
      ]),
    ),
  };
  const { data } = await client.put<RuntimeConfigUiRecord>(
    '/settings/runtime/llm',
    payload,
    withSettingsSectionTelemetry('llm-providers'),
  );
  return toUiRuntimeConfig(data);
}

function buildLlmProviderPayload(config: LlmProviderConfig): Record<string, unknown> {
  return {
    baseUrl: config.baseUrl,
    requestTimeoutSeconds: config.requestTimeoutSeconds,
    apiKey: toSecretPayload(config.apiKey ?? null),
    apiType: normalizeLlmApiType(config.apiType),
    legacyApi: config.legacyApi === true ? true : null,
  };
}

export async function addLlmProvider(name: string, config: LlmProviderConfig): Promise<RuntimeConfig> {
  const { data } = await client.post<RuntimeConfigUiRecord>(
    `/settings/runtime/llm/providers/${name}`,
    buildLlmProviderPayload(config),
    withSettingsSectionTelemetry('llm-providers'),
  );
  return toUiRuntimeConfig(data);
}

export async function updateLlmProvider(name: string, config: LlmProviderConfig): Promise<RuntimeConfig> {
  const { data } = await client.put<RuntimeConfigUiRecord>(
    `/settings/runtime/llm/providers/${name}`,
    buildLlmProviderPayload(config),
    withSettingsSectionTelemetry('llm-providers'),
  );
  return toUiRuntimeConfig(data);
}

export async function removeLlmProvider(name: string): Promise<void> {
  await client.delete(`/settings/runtime/llm/providers/${name}`);
}

export async function addLlmProviderAndImport(
  name: string,
  config: LlmProviderConfig,
  selectedModelIds: string[],
): Promise<LlmProviderImportResult> {
  const { data } = await client.post<LlmProviderImportResult>(
    `/settings/runtime/llm/providers/${name}/import-models`,
    {
      config: buildLlmProviderPayload(config),
      selectedModelIds,
    },
    withSettingsSectionTelemetry('llm-providers'),
  );
  return data;
}

export async function testSavedLlmProvider(name: string): Promise<LlmProviderTestResult> {
  const { data } = await client.post<LlmProviderTestResult>('/settings/runtime/llm/provider-tests', {
    mode: 'saved',
    providerName: name,
    config: null,
  });
  return data;
}

export async function testDraftLlmProvider(name: string, config: LlmProviderConfig): Promise<LlmProviderTestResult> {
  const { data } = await client.post<LlmProviderTestResult>('/settings/runtime/llm/provider-tests', {
    mode: 'draft',
    providerName: name,
    config: buildLlmProviderPayload(config),
  });
  return data;
}

export async function updateToolsConfig(config: ToolsConfig): Promise<RuntimeConfig> {
  const { data } = await client.put<RuntimeConfigUiRecord>(
    '/settings/runtime/tools',
    config,
    withSettingsSectionTelemetry('tools'),
  );
  return toUiRuntimeConfig(data);
}

export async function updateVoiceConfig(config: VoiceConfig): Promise<RuntimeConfig> {
  const { apiKeyPresent: _apiKeyPresent, whisperSttApiKeyPresent: _whisperSttApiKeyPresent, ...voice } = config;
  const payload = {
    ...voice,
    apiKey: toSecretPayload(voice.apiKey ?? null),
    whisperSttApiKey: toSecretPayload(voice.whisperSttApiKey ?? null),
  };
  const { data } = await client.put<RuntimeConfigUiRecord>(
    '/settings/runtime/voice',
    payload,
    withSettingsSectionTelemetry('tool-voice'),
  );
  return toUiRuntimeConfig(data);
}

export async function updateMemoryConfig(config: MemoryConfig): Promise<RuntimeConfig> {
  const { data } = await client.put<RuntimeConfigUiRecord>(
    '/settings/runtime/memory',
    config,
    withSettingsSectionTelemetry('memory'),
  );
  return toUiRuntimeConfig(data);
}

export async function getMemoryPresets(): Promise<MemoryPreset[]> {
  const { data } = await client.get<MemoryPreset[]>('/settings/runtime/memory/presets');
  return data;
}

export async function updateSkillsConfig(config: SkillsConfig): Promise<RuntimeConfig> {
  const { data } = await client.put<RuntimeConfigUiRecord>(
    '/settings/runtime/skills',
    config,
    withSettingsSectionTelemetry('skills'),
  );
  return toUiRuntimeConfig(data);
}

export async function updateTurnConfig(config: TurnConfig): Promise<RuntimeConfig> {
  const { data } = await client.put<RuntimeConfigUiRecord>(
    '/settings/runtime/turn',
    config,
    withSettingsSectionTelemetry('turn'),
  );
  return toUiRuntimeConfig(data);
}

export async function updateUsageConfig(config: UsageConfig): Promise<RuntimeConfig> {
  const { data } = await client.put<RuntimeConfigUiRecord>(
    '/settings/runtime/usage',
    config,
    withSettingsSectionTelemetry('usage'),
  );
  return toUiRuntimeConfig(data);
}

export async function updateTelemetryConfig(config: TelemetryConfig): Promise<RuntimeConfig> {
  const { data } = await client.put<RuntimeConfigUiRecord>(
    '/settings/runtime/telemetry',
    config,
    withSettingsSectionTelemetry('telemetry'),
  );
  return toUiRuntimeConfig(data);
}

export async function updateMcpConfig(config: McpConfig): Promise<RuntimeConfig> {
  const { data } = await client.put<RuntimeConfigUiRecord>(
    '/settings/runtime/mcp',
    config,
    withSettingsSectionTelemetry('mcp'),
  );
  return toUiRuntimeConfig(data);
}

export async function addMcpCatalogEntry(entry: McpCatalogEntry): Promise<RuntimeConfig> {
  const { data } = await client.post<RuntimeConfigUiRecord>(
    '/settings/runtime/mcp/catalog',
    entry,
    withSettingsSectionTelemetry('mcp'),
  );
  return toUiRuntimeConfig(data);
}

export async function updateMcpCatalogEntry(name: string, entry: McpCatalogEntry): Promise<RuntimeConfig> {
  const { data } = await client.put<RuntimeConfigUiRecord>(
    `/settings/runtime/mcp/catalog/${name}`,
    entry,
    withSettingsSectionTelemetry('mcp'),
  );
  return toUiRuntimeConfig(data);
}

export async function removeMcpCatalogEntry(name: string): Promise<void> {
  await client.delete(`/settings/runtime/mcp/catalog/${name}`);
}

export async function updateHiveConfig(config: HiveConfig): Promise<RuntimeConfig> {
  const { data } = await client.put<RuntimeConfigUiRecord>(
    '/settings/runtime/hive',
    config,
    withSettingsSectionTelemetry('hive'),
  );
  return toUiRuntimeConfig(data);
}

export async function updatePlanConfig(config: PlanConfig): Promise<RuntimeConfig> {
  const { data } = await client.put<RuntimeConfigUiRecord>(
    '/settings/runtime/plan',
    config,
    withSettingsSectionTelemetry('plan'),
  );
  return toUiRuntimeConfig(data);
}

export async function updateAutoConfig(config: AutoModeConfig): Promise<RuntimeConfig> {
  const { data } = await client.put<RuntimeConfigUiRecord>(
    '/settings/runtime/auto',
    config,
    withSettingsSectionTelemetry('auto'),
  );
  return toUiRuntimeConfig(data);
}

export async function updateTracingConfig(config: TracingConfig): Promise<RuntimeConfig> {
  const { data } = await client.put<RuntimeConfigUiRecord>(
    '/settings/runtime/tracing',
    config,
    withSettingsSectionTelemetry('tracing'),
  );
  return toUiRuntimeConfig(data);
}

export async function updateAdvancedConfig(config: {
  rateLimit?: RateLimitConfig;
  security?: SecurityConfig;
  compaction?: CompactionConfig;
  resilience?: ResilienceConfig;
}): Promise<RuntimeConfig> {
  const { data } = await client.put<RuntimeConfigUiRecord>(
    '/settings/runtime/advanced',
    config,
    withSettingsSectionTelemetry('advanced'),
  );
  return toUiRuntimeConfig(data);
}
