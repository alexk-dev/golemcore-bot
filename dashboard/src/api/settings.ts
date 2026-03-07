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
  llm?: {
    providers?: Record<string, UnknownRecord>;
  } & UnknownRecord;
  tools?: UnknownRecord;
  voice?: UnknownRecord;
}

function toUiRuntimeConfig(data: RuntimeConfigUiRecord): RuntimeConfig {
  const cfg: RuntimeConfigUiRecord = { ...data };
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

function toBackendWebhookConfig(config: WebhookConfig): UnknownRecord {
  return {
    ...config,
    token: toSecretPayload(config.token ?? null),
    mappings: (config.mappings ?? []).map((mapping) => ({
      ...mapping,
      hmacSecret: toSecretPayload(mapping.hmacSecret ?? null),
    })),
  };
}

export interface SettingsResponse extends Record<string, unknown> {
  language?: string;
  timezone?: string;
  modelTier?: string | null;
  tierForce?: boolean;
  webhooks?: WebhookConfig;
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
  llm: LlmConfig;
  tools: ToolsConfig;
  voice: VoiceConfig;
  memory: MemoryConfig;
  skills: SkillsConfig;
  turn: TurnConfig;
  usage: UsageConfig;
  mcp: McpConfig;
  autoMode: AutoModeConfig;
  rateLimit: RateLimitConfig;
  security: SecurityConfig;
  compaction: CompactionConfig;
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
}

export interface MemoryPreset {
  id: string;
  label: string;
  comment: string;
  memory: MemoryConfig;
}

export interface SkillsConfig {
  enabled: boolean | null;
  progressiveLoading: boolean | null;
}

export interface TurnConfig {
  maxLlmCalls: number | null;
  maxToolExecutions: number | null;
  deadline: string | null;
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
  routingModel: string | null;
  routingModelReasoning: string | null;
  balancedModel: string | null;
  balancedModelReasoning: string | null;
  smartModel: string | null;
  smartModelReasoning: string | null;
  codingModel: string | null;
  codingModelReasoning: string | null;
  deepModel: string | null;
  deepModelReasoning: string | null;
  dynamicTierEnabled: boolean | null;
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

export interface AutoModeConfig {
  enabled: boolean | null;
  tickIntervalSeconds: number | null;
  taskTimeLimitMinutes: number | null;
  autoStart: boolean | null;
  maxGoals: number | null;
  modelTier: string | null;
  notifyMilestones: boolean | null;
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
  maxContextTokens: number | null;
  keepLastMessages: number | null;
}

export interface WebhookConfig {
  enabled: boolean;
  token: string | null;
  maxPayloadSize: number;
  defaultTimeoutSeconds: number;
  mappings: HookMapping[];
}

export interface HookMapping {
  name: string;
  action: string;
  authMode: string;
  hmacHeader: string | null;
  hmacSecret: string | null;
  hmacPrefix: string | null;
  messageTemplate: string | null;
  model: string | null;
  deliver: boolean;
  channel: string | null;
  to: string | null;
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

export async function updateWebhooksConfig(config: WebhookConfig): Promise<void> {
  await client.put('/settings/runtime/webhooks', toBackendWebhookConfig(config));
}

export async function updateAutoConfig(config: AutoModeConfig): Promise<RuntimeConfig> {
  const { data } = await client.put<RuntimeConfigUiRecord>('/settings/runtime/auto', config);
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
