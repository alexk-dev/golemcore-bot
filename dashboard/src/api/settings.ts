import client from './client';

export async function getSettings() {
  const { data } = await client.get('/settings');
  return data;
}

export async function updatePreferences(prefs: Record<string, unknown>) {
  const { data } = await client.put('/settings/preferences', prefs);
  return data;
}

export async function getModels() {
  const { data } = await client.get('/settings/models');
  return data;
}

export async function updateTierOverrides(overrides: Record<string, { model: string; reasoning: string }>) {
  const { data } = await client.put('/settings/tier-overrides', overrides);
  return data;
}

// ==================== Runtime Config ====================

export interface RuntimeConfig {
  telegram: TelegramConfig;
  modelRouter: ModelRouterConfig;
  tools: ToolsConfig;
  voice: VoiceConfig;
  memory: MemoryConfig;
  skills: SkillsConfig;
  turn: TurnConfig;
  usage: UsageConfig;
  rag: RagConfig;
  autoMode: AutoModeConfig;
  rateLimit: RateLimitConfig;
  security: SecurityConfig;
  compaction: CompactionConfig;
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
  imapEnabled: boolean | null;
  smtpEnabled: boolean | null;
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
  const { data } = await client.get('/settings/runtime');
  return data;
}

export async function updateRuntimeConfig(config: RuntimeConfig): Promise<RuntimeConfig> {
  const { data } = await client.put('/settings/runtime', config);
  return data;
}

export async function updateTelegramConfig(config: TelegramConfig): Promise<RuntimeConfig> {
  const { data } = await client.put('/settings/runtime/telegram', config);
  return data;
}

export async function updateModelRouterConfig(config: ModelRouterConfig): Promise<RuntimeConfig> {
  const { data } = await client.put('/settings/runtime/models', config);
  return data;
}

export async function updateToolsConfig(config: ToolsConfig): Promise<RuntimeConfig> {
  const { data } = await client.put('/settings/runtime/tools', config);
  return data;
}

export async function updateVoiceConfig(config: VoiceConfig): Promise<RuntimeConfig> {
  const { data } = await client.put('/settings/runtime/voice', config);
  return data;
}

export async function updateMemoryConfig(config: MemoryConfig): Promise<RuntimeConfig> {
  const { data } = await client.put('/settings/runtime/memory', config);
  return data;
}

export async function updateSkillsConfig(config: SkillsConfig): Promise<RuntimeConfig> {
  const { data } = await client.put('/settings/runtime/skills', config);
  return data;
}

export async function updateTurnConfig(config: TurnConfig): Promise<RuntimeConfig> {
  const { data } = await client.put('/settings/runtime/turn', config);
  return data;
}

export async function updateUsageConfig(config: UsageConfig): Promise<RuntimeConfig> {
  const { data } = await client.put('/settings/runtime/usage', config);
  return data;
}

export async function updateRagConfig(config: RagConfig): Promise<RuntimeConfig> {
  const { data } = await client.put('/settings/runtime/rag', config);
  return data;
}

export async function updateWebhooksConfig(config: WebhookConfig): Promise<void> {
  await client.put('/settings/runtime/webhooks', config);
}

export async function updateAutoConfig(config: AutoModeConfig): Promise<RuntimeConfig> {
  const { data } = await client.put('/settings/runtime/auto', config);
  return data;
}

export async function updateAdvancedConfig(config: {
  rateLimit?: RateLimitConfig;
  security?: SecurityConfig;
  compaction?: CompactionConfig;
}): Promise<RuntimeConfig> {
  const { data } = await client.put('/settings/runtime/advanced', config);
  return data;
}

export async function generateInviteCode(): Promise<InviteCode> {
  const params = {};
  const { data } = await client.post('/settings/telegram/invite-codes', null, { params });
  return data;
}

export async function deleteInviteCode(code: string): Promise<void> {
  await client.delete(`/settings/telegram/invite-codes/${code}`);
}

export async function restartTelegram(): Promise<void> {
  await client.post('/settings/telegram/restart');
}
