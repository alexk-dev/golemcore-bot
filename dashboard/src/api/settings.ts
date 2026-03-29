import type { ExplicitModelTierId } from '../lib/modelTiers';
import client from './client';

interface SecretPayload {
  value: string | null;
  encrypted: boolean;
}

function toSecretPayload(value: string | null | undefined): SecretPayload | null {
  if (value == null || value === '') {
    return null;
  }
  return { value, encrypted: false };
}

function scrubSecret(): null {
  return null;
}

function hasSecretValue(secret: unknown): boolean {
  if (secret == null || typeof secret !== 'object') {
    return false;
  }
  const value = (secret as UnknownRecord).value;
  const present = (secret as UnknownRecord).present;
  return Boolean(present) || (typeof value === 'string' && value.length > 0);
}

const SUPPORTED_LLM_API_TYPES = ['openai', 'anthropic', 'gemini'] as const;
type SupportedLlmApiType = (typeof SUPPORTED_LLM_API_TYPES)[number];

function isSupportedLlmApiType(value: string): value is SupportedLlmApiType {
  return (SUPPORTED_LLM_API_TYPES as readonly string[]).includes(value);
}

function normalizeLlmApiType(value: unknown): SupportedLlmApiType | null {
  if (typeof value !== 'string') {
    return null;
  }
  const normalized = value.trim().toLowerCase();
  return isSupportedLlmApiType(normalized) ? normalized : null;
}

type UnknownRecord = Record<string, unknown>;

function toShellEnvironmentVariables(value: unknown): ShellEnvironmentVariable[] {
  if (!Array.isArray(value)) {
    return [];
  }
  const normalized: ShellEnvironmentVariable[] = [];
  value.forEach((entry) => {
    if (entry == null || typeof entry !== 'object') {
      return;
    }
    const record = entry as UnknownRecord;
    const nameRaw = record.name;
    const valueRaw = record.value;
    if (typeof nameRaw !== 'string') {
      return;
    }
    const name = nameRaw.trim();
    if (name.length === 0) {
      return;
    }
    normalized.push({
      name,
      value: typeof valueRaw === 'string' ? valueRaw : '',
    });
  });
  return normalized;
}

interface RuntimeConfigUiRecord extends UnknownRecord {
  telegram?: UnknownRecord;
  modelRouter?: unknown;
  llm?: {
    providers?: Record<string, UnknownRecord>;
  } & UnknownRecord;
  tools?: UnknownRecord;
  voice?: UnknownRecord;
  hive?: UnknownRecord;
  modelRegistry?: unknown;
}

function toUiRuntimeConfig(data: RuntimeConfigUiRecord): RuntimeConfig {
  const cfg: RuntimeConfigUiRecord = {
    ...data,
    plan: typeof data.plan === 'object' && data.plan != null ? { ...data.plan } : {},
    hive: typeof data.hive === 'object' && data.hive != null ? { ...data.hive } : {},
  };
  if (cfg.telegram) {
    cfg.telegram = {
      ...cfg.telegram,
      token: scrubSecret(),
      tokenPresent: hasSecretValue(cfg.telegram.token),
    };
  }
  if (cfg.llm?.providers != null) {
    const providers: Record<string, UnknownRecord> = {};
    Object.entries(cfg.llm.providers).forEach(([name, provider]) => {
      const providerApiKey = provider.apiKey as UnknownRecord | undefined;
      const hasApiKey = hasSecretValue(providerApiKey);
      const apiType = normalizeLlmApiType(provider.apiType);
      providers[name] = {
        ...provider,
        apiKey: scrubSecret(),
        apiKeyPresent: hasApiKey,
        apiType,
      };
    });
    cfg.llm = { ...cfg.llm, providers };
  }
  if (cfg.tools) {
    cfg.tools = {
      ...cfg.tools,
      shellEnvironmentVariables: toShellEnvironmentVariables(cfg.tools.shellEnvironmentVariables),
    };
  }
  if (cfg.voice) {
    cfg.voice = {
      ...cfg.voice,
      apiKey: scrubSecret(),
      apiKeyPresent: hasSecretValue(cfg.voice.apiKey),
      whisperSttApiKey: scrubSecret(),
      whisperSttApiKeyPresent: hasSecretValue(cfg.voice.whisperSttApiKey),
    };
  }
  cfg.modelRouter = toModelRouterConfig(cfg.modelRouter);
  cfg.modelRegistry = toModelRegistryConfig(cfg.modelRegistry);
  return cfg as unknown as RuntimeConfig;
}

function toBackendRuntimeConfig(config: RuntimeConfig): UnknownRecord {
  const { tokenPresent: _telegramTokenPresent, ...telegram } = config.telegram;
  const { apiKeyPresent: _voiceApiKeyPresent, whisperSttApiKeyPresent: _whisperSttApiKeyPresent, ...voice } = config.voice;
  const tools = config.tools;

  const payload: UnknownRecord = {
    ...config,
    telegram: {
      ...telegram,
      token: toSecretPayload(telegram.token ?? null),
    },
    llm: {
      ...config.llm,
      providers: Object.fromEntries(
        Object.entries(config.llm.providers).map(([name, provider]) => [
          name,
          {
            baseUrl: provider.baseUrl,
            requestTimeoutSeconds: provider.requestTimeoutSeconds,
            apiKey: toSecretPayload(provider.apiKey ?? null),
            apiType: normalizeLlmApiType(provider.apiType),
          },
        ]),
      ),
    },
    tools: {
      ...tools,
    },
    voice: {
      ...voice,
      apiKey: toSecretPayload(voice.apiKey ?? null),
      whisperSttApiKey: toSecretPayload(voice.whisperSttApiKey ?? null),
    },
  };
  return payload;
}

export interface SettingsResponse extends Record<string, unknown> {
  language?: string;
  timezone?: string;
  modelTier?: string | null;
  tierForce?: boolean;
}

export async function getSettings(): Promise<SettingsResponse> {
  const { data } = await client.get<SettingsResponse>('/settings');
  return data;
}

export async function updatePreferences(prefs: Record<string, unknown>): Promise<SettingsResponse> {
  const { data } = await client.put<SettingsResponse>('/settings/preferences', prefs);
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

export interface RuntimeConfig {
  telegram: TelegramConfig;
  modelRouter: ModelRouterConfig;
  modelRegistry: ModelRegistryConfig;
  llm: LlmConfig;
  tools: ToolsConfig;
  voice: VoiceConfig;
  memory: MemoryConfig;
  skills: SkillsConfig;
  turn: TurnConfig;
  usage: UsageConfig;
  mcp: McpConfig;
  plan: PlanConfig;
  hive: HiveConfig;
  autoMode: AutoModeConfig;
  tracing: TracingConfig;
  rateLimit: RateLimitConfig;
  security: SecurityConfig;
  compaction: CompactionConfig;
}

export interface ModelRegistryConfig {
  repositoryUrl: string | null;
  branch: string | null;
}

export interface LlmConfig {
  providers: Record<string, LlmProviderConfig>;
}

export type ApiType = SupportedLlmApiType;

export interface LlmProviderConfig {
  apiKey: string | null;
  apiKeyPresent?: boolean;
  baseUrl: string | null;
  requestTimeoutSeconds: number | null;
  apiType: ApiType | null;
}

export interface MemoryConfig {
  enabled: boolean | null;
  softPromptBudgetTokens: number | null;
  maxPromptBudgetTokens: number | null;
  workingTopK: number | null;
  episodicTopK: number | null;
  semanticTopK: number | null;
  proceduralTopK: number | null;
  promotionEnabled: boolean | null;
  promotionMinConfidence: number | null;
  decayEnabled: boolean | null;
  decayDays: number | null;
  retrievalLookbackDays: number | null;
  codeAwareExtractionEnabled: boolean | null;
  disclosure?: MemoryDisclosureConfig | null;
  diagnostics?: MemoryDiagnosticsConfig | null;
}

export interface MemoryPreset {
  id: string;
  label: string;
  comment: string;
  memory: MemoryConfig;
}

export type MemoryDisclosureMode = 'index' | 'summary' | 'selective_detail' | 'full_pack';

export type MemoryPromptStyle = 'compact' | 'balanced' | 'rich';

export type MemoryDiagnosticsVerbosity = 'off' | 'basic' | 'detailed';

export interface MemoryDisclosureConfig {
  mode: MemoryDisclosureMode | null;
  promptStyle: MemoryPromptStyle | null;
  toolExpansionEnabled: boolean | null;
  disclosureHintsEnabled: boolean | null;
  detailMinScore: number | null;
}

export interface MemoryDiagnosticsConfig {
  verbosity: MemoryDiagnosticsVerbosity | null;
}

export interface SkillsConfig {
  enabled: boolean | null;
  progressiveLoading: boolean | null;
  marketplaceSourceType: 'repository' | 'directory' | 'sandbox' | null;
  marketplaceRepositoryDirectory: string | null;
  marketplaceSandboxPath: string | null;
  marketplaceRepositoryUrl: string | null;
  marketplaceBranch: string | null;
}

export interface TurnConfig {
  maxLlmCalls: number | null;
  maxToolExecutions: number | null;
  deadline: string | null;
  progressUpdatesEnabled: boolean | null;
  progressIntentEnabled: boolean | null;
  progressBatchSize: number | null;
  progressMaxSilenceSeconds: number | null;
  progressSummaryTimeoutMs: number | null;
}

export interface TelegramConfig {
  enabled: boolean | null;
  token: string | null;
  tokenPresent?: boolean;
  authMode: 'invite_only' | null;
  allowedUsers: string[];
  inviteCodes: InviteCode[];
}

export interface InviteCode {
  code: string;
  used: boolean;
  createdAt: string;
}

export interface ModelRouterConfig {
  temperature: number | null;
  routing: TierBinding;
  tiers: Record<ExplicitModelTierId, TierBinding>;
  dynamicTierEnabled: boolean | null;
}

export interface TierBinding {
  model: string | null;
  reasoning: string | null;
}

export interface ToolsConfig {
  filesystemEnabled: boolean | null;
  shellEnabled: boolean | null;
  skillManagementEnabled: boolean | null;
  skillTransitionEnabled: boolean | null;
  tierEnabled: boolean | null;
  goalManagementEnabled: boolean | null;
  shellEnvironmentVariables: ShellEnvironmentVariable[];
}

export interface ShellEnvironmentVariable {
  name: string;
  value: string;
}

export interface VoiceConfig {
  enabled: boolean | null;
  apiKey: string | null;
  apiKeyPresent?: boolean;
  voiceId: string | null;
  ttsModelId: string | null;
  sttModelId: string | null;
  speed: number | null;
  telegramRespondWithVoice: boolean | null;
  telegramTranscribeIncoming: boolean | null;
  sttProvider: string | null;
  ttsProvider: string | null;
  whisperSttUrl: string | null;
  whisperSttApiKey: string | null;
  whisperSttApiKeyPresent?: boolean;
}

export interface UsageConfig {
  enabled: boolean | null;
}

export interface PlanConfig {
  enabled: boolean | null;
  maxPlans: number | null;
  maxStepsPerPlan: number | null;
  stopOnFailure: boolean | null;
}

export interface HiveConfig {
  enabled: boolean | null;
  serverUrl: string | null;
  displayName: string | null;
  hostLabel: string | null;
  autoConnect: boolean | null;
  managedByProperties: boolean | null;
}

export interface AutoModeConfig {
  enabled: boolean | null;
  tickIntervalSeconds: number | null;
  taskTimeLimitMinutes: number | null;
  autoStart: boolean | null;
  maxGoals: number | null;
  modelTier: string | null;
  reflectionEnabled: boolean | null;
  reflectionFailureThreshold: number | null;
  reflectionModelTier: string | null;
  reflectionTierPriority: boolean | null;
  notifyMilestones: boolean | null;
}

export interface TracingConfig {
  enabled: boolean | null;
  payloadSnapshotsEnabled: boolean | null;
  sessionTraceBudgetMb: number | null;
  maxSnapshotSizeKb: number | null;
  maxSnapshotsPerSpan: number | null;
  maxTracesPerSession: number | null;
  captureInboundPayloads: boolean | null;
  captureOutboundPayloads: boolean | null;
  captureToolPayloads: boolean | null;
  captureLlmPayloads: boolean | null;
}

export interface RateLimitConfig {
  enabled: boolean | null;
  userRequestsPerMinute: number | null;
  userRequestsPerHour: number | null;
  userRequestsPerDay: number | null;
}

export interface SecurityConfig {
  sanitizeInput: boolean | null;
  detectPromptInjection: boolean | null;
  detectCommandInjection: boolean | null;
  maxInputLength: number | null;
  allowlistEnabled: boolean | null;
  toolConfirmationEnabled: boolean | null;
  toolConfirmationTimeoutSeconds: number | null;
}

export interface McpConfig {
  enabled: boolean | null;
  defaultStartupTimeout: number | null;
  defaultIdleTimeout: number | null;
}

export interface CompactionConfig {
  enabled: boolean | null;
  triggerMode: 'model_ratio' | 'token_threshold' | null;
  modelThresholdRatio: number | null;
  maxContextTokens: number | null;
  keepLastMessages: number | null;
}

export async function getRuntimeConfig(): Promise<RuntimeConfig> {
  const { data } = await client.get<RuntimeConfigUiRecord>('/settings/runtime');
  return toUiRuntimeConfig(data);
}

export async function updateRuntimeConfig(config: RuntimeConfig): Promise<RuntimeConfig> {
  const { data } = await client.put<RuntimeConfigUiRecord>('/settings/runtime', toBackendRuntimeConfig(config));
  return toUiRuntimeConfig(data);
}

export async function updateModelRouterConfig(config: ModelRouterConfig): Promise<RuntimeConfig> {
  const { data } = await client.put<RuntimeConfigUiRecord>('/settings/runtime/models', config);
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
        },
      ]),
    ),
  };
  const { data } = await client.put<RuntimeConfigUiRecord>('/settings/runtime/llm', payload);
  return toUiRuntimeConfig(data);
}

export async function addLlmProvider(name: string, config: LlmProviderConfig): Promise<RuntimeConfig> {
  const payload = {
    baseUrl: config.baseUrl,
    requestTimeoutSeconds: config.requestTimeoutSeconds,
    apiKey: toSecretPayload(config.apiKey ?? null),
    apiType: normalizeLlmApiType(config.apiType),
  };
  const { data } = await client.post<RuntimeConfigUiRecord>(`/settings/runtime/llm/providers/${name}`, payload);
  return toUiRuntimeConfig(data);
}

export async function updateLlmProvider(name: string, config: LlmProviderConfig): Promise<RuntimeConfig> {
  const payload = {
    baseUrl: config.baseUrl,
    requestTimeoutSeconds: config.requestTimeoutSeconds,
    apiKey: toSecretPayload(config.apiKey ?? null),
    apiType: normalizeLlmApiType(config.apiType),
  };
  const { data } = await client.put<RuntimeConfigUiRecord>(`/settings/runtime/llm/providers/${name}`, payload);
  return toUiRuntimeConfig(data);
}

export async function removeLlmProvider(name: string): Promise<void> {
  await client.delete(`/settings/runtime/llm/providers/${name}`);
}

export async function updateToolsConfig(config: ToolsConfig): Promise<RuntimeConfig> {
  const { data } = await client.put<RuntimeConfigUiRecord>('/settings/runtime/tools', config);
  return toUiRuntimeConfig(data);
}

export async function updateVoiceConfig(config: VoiceConfig): Promise<RuntimeConfig> {
  const { apiKeyPresent: _apiKeyPresent, whisperSttApiKeyPresent: _whisperSttApiKeyPresent, ...voice } = config;
  const payload = {
    ...voice,
    apiKey: toSecretPayload(voice.apiKey ?? null),
    whisperSttApiKey: toSecretPayload(voice.whisperSttApiKey ?? null),
  };
  const { data } = await client.put<RuntimeConfigUiRecord>('/settings/runtime/voice', payload);
  return toUiRuntimeConfig(data);
}

export async function updateMemoryConfig(config: MemoryConfig): Promise<RuntimeConfig> {
  const { data } = await client.put<RuntimeConfigUiRecord>('/settings/runtime/memory', config);
  return toUiRuntimeConfig(data);
}

export async function getMemoryPresets(): Promise<MemoryPreset[]> {
  const { data } = await client.get<MemoryPreset[]>('/settings/runtime/memory/presets');
  return data;
}

export async function updateSkillsConfig(config: SkillsConfig): Promise<RuntimeConfig> {
  const { data } = await client.put<RuntimeConfigUiRecord>('/settings/runtime/skills', config);
  return toUiRuntimeConfig(data);
}

export async function updateTurnConfig(config: TurnConfig): Promise<RuntimeConfig> {
  const { data } = await client.put<RuntimeConfigUiRecord>('/settings/runtime/turn', config);
  return toUiRuntimeConfig(data);
}

export async function updateUsageConfig(config: UsageConfig): Promise<RuntimeConfig> {
  const { data } = await client.put<RuntimeConfigUiRecord>('/settings/runtime/usage', config);
  return toUiRuntimeConfig(data);
}

export async function updateMcpConfig(config: McpConfig): Promise<RuntimeConfig> {
  const { data } = await client.put<RuntimeConfigUiRecord>('/settings/runtime/mcp', config);
  return toUiRuntimeConfig(data);
}

export async function updateHiveConfig(config: HiveConfig): Promise<RuntimeConfig> {
  const { data } = await client.put<RuntimeConfigUiRecord>('/settings/runtime/hive', config);
  return toUiRuntimeConfig(data);
}

export async function updatePlanConfig(config: PlanConfig): Promise<RuntimeConfig> {
  const { data } = await client.put<RuntimeConfigUiRecord>('/settings/runtime/plan', config);
  return toUiRuntimeConfig(data);
}

export async function updateAutoConfig(config: AutoModeConfig): Promise<RuntimeConfig> {
  const { data } = await client.put<RuntimeConfigUiRecord>('/settings/runtime/auto', config);
  return toUiRuntimeConfig(data);
}

export async function updateTracingConfig(config: TracingConfig): Promise<RuntimeConfig> {
  const { data } = await client.put<RuntimeConfigUiRecord>('/settings/runtime/tracing', config);
  return toUiRuntimeConfig(data);
}

export async function updateAdvancedConfig(config: {
  rateLimit?: RateLimitConfig;
  security?: SecurityConfig;
  compaction?: CompactionConfig;
}): Promise<RuntimeConfig> {
  const { data } = await client.put<RuntimeConfigUiRecord>('/settings/runtime/advanced', config);
  return toUiRuntimeConfig(data);
}

function toNullableString(value: unknown): string | null {
  if (typeof value !== 'string') {
    return null;
  }
  const normalized = value.trim();
  return normalized.length > 0 ? normalized : null;
}

function toModelRegistryConfig(value: unknown): ModelRegistryConfig {
  const record = value != null && typeof value === 'object' ? value as UnknownRecord : {};
  return {
    repositoryUrl: toNullableString(record.repositoryUrl),
    branch: toNullableString(record.branch) ?? 'main',
  };
}

function toTierBinding(value: unknown): TierBinding {
  if (value == null || typeof value !== 'object') {
    return {
      model: null,
      reasoning: null,
    };
  }
  const record = value as UnknownRecord;
  return {
    model: toNullableString(record.model),
    reasoning: toNullableString(record.reasoning),
  };
}

function toModelRouterConfig(value: unknown): ModelRouterConfig {
  const record = value != null && typeof value === 'object' ? value as UnknownRecord : {};
  const rawTiers = record.tiers != null && typeof record.tiers === 'object'
    ? record.tiers as Record<string, unknown>
    : {};

  return {
    temperature: typeof record.temperature === 'number' ? record.temperature : null,
    routing: toTierBinding(record.routing),
    tiers: {
      balanced: toTierBinding(rawTiers.balanced),
      smart: toTierBinding(rawTiers.smart),
      deep: toTierBinding(rawTiers.deep),
      coding: toTierBinding(rawTiers.coding),
      special1: toTierBinding(rawTiers.special1),
      special2: toTierBinding(rawTiers.special2),
      special3: toTierBinding(rawTiers.special3),
      special4: toTierBinding(rawTiers.special4),
      special5: toTierBinding(rawTiers.special5),
    },
    dynamicTierEnabled: typeof record.dynamicTierEnabled === 'boolean' ? record.dynamicTierEnabled : null,
  };
}
