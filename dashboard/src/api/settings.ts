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

interface RuntimeConfigUiRecord extends UnknownRecord {
  telegram?: UnknownRecord;
  llm?: {
    providers?: Record<string, UnknownRecord>;
  } & UnknownRecord;
  tools?: {
    imap?: UnknownRecord;
    smtp?: UnknownRecord;
  } & UnknownRecord;
  voice?: UnknownRecord;
  rag?: UnknownRecord;
}

function toUiRuntimeConfig(data: RuntimeConfigUiRecord): RuntimeConfig {
  const cfg: RuntimeConfigUiRecord = { ...data };
  if (cfg.telegram) {
    cfg.telegram = { ...cfg.telegram, token: scrubSecret() };
  }
  if (cfg.llm?.providers != null) {
    const providers: Record<string, UnknownRecord> = {};
    Object.entries(cfg.llm.providers).forEach(([name, provider]) => {
      const providerApiKey = provider.apiKey as UnknownRecord | undefined;
      const hasApiKey = Boolean(providerApiKey?.present) || Boolean(providerApiKey?.value);
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
      braveSearchApiKey: scrubSecret(),
      imap: cfg.tools.imap ? { ...cfg.tools.imap, password: scrubSecret() } : cfg.tools.imap,
      smtp: cfg.tools.smtp ? { ...cfg.tools.smtp, password: scrubSecret() } : cfg.tools.smtp,
    };
  }
  if (cfg.voice) {
    cfg.voice = { ...cfg.voice, apiKey: scrubSecret() };
  }
  if (cfg.rag) {
    cfg.rag = { ...cfg.rag, apiKey: scrubSecret() };
  }
  return cfg as unknown as RuntimeConfig;
}

function toBackendRuntimeConfig(config: RuntimeConfig): UnknownRecord {
  const payload: UnknownRecord = {
    ...config,
    telegram: {
      ...config.telegram,
      token: toSecretPayload(config.telegram?.token ?? null),
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
      ...config.tools,
      braveSearchApiKey: toSecretPayload(config.tools.braveSearchApiKey ?? null),
      imap: config.tools.imap
        ? {
          ...config.tools.imap,
          password: toSecretPayload(config.tools.imap.password ?? null),
        }
        : config.tools.imap,
      smtp: config.tools.smtp
        ? {
          ...config.tools.smtp,
          password: toSecretPayload(config.tools.smtp.password ?? null),
        }
        : config.tools.smtp,
    },
    voice: {
      ...config.voice,
      apiKey: toSecretPayload(config.voice.apiKey ?? null),
    },
    rag: {
      ...config.rag,
      apiKey: toSecretPayload(config.rag.apiKey ?? null),
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
  rag: RagConfig;
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
  recentDays: number | null;
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
  authMode: 'user' | 'invite_only' | null;
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
  browserEnabled: boolean | null;
  browserType: string | null;
  browserTimeout: number | null;
  browserUserAgent: string | null;
  filesystemEnabled: boolean | null;
  shellEnabled: boolean | null;
  browserApiProvider: string | null;
  browserHeadless: boolean | null;
  braveSearchEnabled: boolean | null;
  braveSearchApiKey: string | null;
  skillManagementEnabled: boolean | null;
  skillTransitionEnabled: boolean | null;
  tierEnabled: boolean | null;
  goalManagementEnabled: boolean | null;
  imap: ImapConfig;
  smtp: SmtpConfig;
}

export interface ImapConfig {
  enabled: boolean | null;
  host: string | null;
  port: number | null;
  username: string | null;
  password: string | null;
  security: string | null;
  sslTrust: string | null;
  connectTimeout: number | null;
  readTimeout: number | null;
  maxBodyLength: number | null;
  defaultMessageLimit: number | null;
}

export interface SmtpConfig {
  enabled: boolean | null;
  host: string | null;
  port: number | null;
  username: string | null;
  password: string | null;
  security: string | null;
  sslTrust: string | null;
  connectTimeout: number | null;
  readTimeout: number | null;
}

export interface VoiceConfig {
  enabled: boolean | null;
  apiKey: string | null;
  voiceId: string | null;
  ttsModelId: string | null;
  sttModelId: string | null;
  speed: number | null;
  telegramRespondWithVoice: boolean | null;
  telegramTranscribeIncoming: boolean | null;
}

export interface UsageConfig {
  enabled: boolean | null;
}

export interface RagConfig {
  enabled: boolean | null;
  url: string | null;
  apiKey: string | null;
  queryMode: string | null;
  timeoutSeconds: number | null;
  indexMinLength: number | null;
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

export async function updateTelegramConfig(config: TelegramConfig): Promise<RuntimeConfig> {
  const payload = { ...config, token: toSecretPayload(config.token ?? null) };
  const { data } = await client.put<RuntimeConfigUiRecord>('/settings/runtime/telegram', payload);
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
  const payload = {
    ...config,
    braveSearchApiKey: toSecretPayload(config.braveSearchApiKey ?? null),
    imap: config.imap
      ? {
        ...config.imap,
        password: toSecretPayload(config.imap.password ?? null),
      }
      : config.imap,
    smtp: config.smtp
      ? {
        ...config.smtp,
        password: toSecretPayload(config.smtp.password ?? null),
      }
      : config.smtp,
  };
  const { data } = await client.put<RuntimeConfigUiRecord>('/settings/runtime/tools', payload);
  return toUiRuntimeConfig(data);
}

export async function updateVoiceConfig(config: VoiceConfig): Promise<RuntimeConfig> {
  const payload = { ...config, apiKey: toSecretPayload(config.apiKey ?? null) };
  const { data } = await client.put<RuntimeConfigUiRecord>('/settings/runtime/voice', payload);
  return toUiRuntimeConfig(data);
}

export async function updateMemoryConfig(config: MemoryConfig): Promise<RuntimeConfig> {
  const { data } = await client.put<RuntimeConfigUiRecord>('/settings/runtime/memory', config);
  return toUiRuntimeConfig(data);
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

export async function updateRagConfig(config: RagConfig): Promise<RuntimeConfig> {
  const payload = { ...config, apiKey: toSecretPayload(config.apiKey ?? null) };
  const { data } = await client.put<RuntimeConfigUiRecord>('/settings/runtime/rag', payload);
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

export async function generateInviteCode(): Promise<InviteCode> {
  const { data } = await client.post<InviteCode>('/settings/telegram/invite-codes', null, { params: {} });
  return data;
}

export async function deleteInviteCode(code: string): Promise<void> {
  await client.delete(`/settings/telegram/invite-codes/${code}`);
}

export async function restartTelegram(): Promise<void> {
  await client.post('/settings/telegram/restart');
}
