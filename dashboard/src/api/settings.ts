import { isExplicitModelTier, type ExplicitModelTierId } from '../lib/modelTiers';
import client, { type TelemetryRequestConfig } from './client';

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

const DEFAULT_SELF_EVOLVING_TACTIC_EMBEDDING_PROVIDER = 'ollama';
const DEFAULT_SELF_EVOLVING_TACTIC_LOCAL_MODEL = 'qwen3-embedding:0.6b';

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
  telemetry?: unknown;
  hive?: UnknownRecord;
  selfEvolving?: unknown;
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
        legacyApi: provider.legacyApi === true ? true : null,
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
  cfg.selfEvolving = toSelfEvolvingConfig(cfg.selfEvolving);
  cfg.modelRegistry = toModelRegistryConfig(cfg.modelRegistry);
  return cfg as unknown as RuntimeConfig;
}

function toBackendRuntimeConfig(config: RuntimeConfig): UnknownRecord {
  const { tokenPresent: _telegramTokenPresent, ...telegram } = config.telegram;
  const { apiKeyPresent: _voiceApiKeyPresent, whisperSttApiKeyPresent: _whisperSttApiKeyPresent, ...voice } = config.voice;
  const tools = config.tools;
  const normalizedSelfEvolving = toSelfEvolvingConfig(config.selfEvolving);
  const {
    managedByProperties: _selfEvolvingManagedByProperties,
    overriddenPaths: _selfEvolvingOverriddenPaths,
    ...selfEvolving
  } = normalizedSelfEvolving;

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
            legacyApi: provider.legacyApi === true ? true : null,
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
    selfEvolving: toBackendSelfEvolvingConfig(selfEvolving),
  };
  return payload;
}

function toBackendSelfEvolvingConfig(selfEvolving: UnknownRecord): UnknownRecord {
  const tactics = selfEvolving.tactics as UnknownRecord | undefined;
  if (tactics == null) {
    return selfEvolving;
  }
  const search = tactics.search as UnknownRecord | undefined;
  if (search == null) {
    return selfEvolving;
  }
  const embeddings = search.embeddings as UnknownRecord | undefined;
  if (embeddings == null) {
    return selfEvolving;
  }
  const { apiKey, apiKeyPresent: _apiKeyPresent, ...embeddingsRest } = embeddings;
  const apiKeyValue = typeof apiKey === 'string' ? apiKey : null;
  return {
    ...selfEvolving,
    tactics: {
      ...tactics,
      search: {
        ...search,
        embeddings: {
          ...embeddingsRest,
          apiKey: toSecretPayload(apiKeyValue),
        },
      },
    },
  };
}

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
  telemetry?: TelemetryConfig;
  mcp: McpConfig;
  plan: PlanConfig;
  hive: HiveConfig;
  selfEvolving: SelfEvolvingConfig;
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
  legacyApi: boolean | null;
}

export interface LlmProviderImportResult {
  providerSaved: boolean;
  providerName: string;
  resolvedEndpoint: string | null;
  addedModels: string[];
  skippedModels: string[];
  errors: string[];
}

export type LlmProviderTestMode = 'saved' | 'draft';

export interface LlmProviderTestResult {
  mode: LlmProviderTestMode;
  providerName: string;
  resolvedEndpoint: string | null;
  models: string[];
  success: boolean;
  error: string | null;
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

export interface ModelReference {
  provider: string | null;
  id: string | null;
}

export interface TierBinding {
  model: ModelReference | null;
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

export interface TelemetryConfig {
  enabled: boolean | null;
  clientId?: string;
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

export interface SelfEvolvingConfig {
  enabled: boolean | null;
  tracePayloadOverride: boolean | null;
  managedByProperties?: boolean | null;
  overriddenPaths?: string[];
  capture: SelfEvolvingCaptureConfig;
  judge: SelfEvolvingJudgeConfig;
  evolution: SelfEvolvingEvolutionConfig;
  tactics: SelfEvolvingTacticsConfig;
  promotion: SelfEvolvingPromotionConfig;
  benchmark: SelfEvolvingBenchmarkConfig;
  hive: SelfEvolvingHiveConfig;
}

export interface SelfEvolvingCaptureConfig {
  llm: string | null;
  tool: string | null;
  context: string | null;
  skill: string | null;
  tier: string | null;
  infra: string | null;
}

export interface SelfEvolvingJudgeConfig {
  enabled: boolean | null;
  primaryTier: string | null;
  tiebreakerTier: string | null;
  evolutionTier: string | null;
  requireEvidenceAnchors: boolean | null;
  uncertaintyThreshold: number | null;
}

export interface SelfEvolvingEvolutionConfig {
  enabled: boolean | null;
  modes: string[];
  artifactTypes: string[];
}

export interface SelfEvolvingTacticsConfig {
  enabled: boolean | null;
  search: SelfEvolvingTacticSearchConfig;
}

export interface SelfEvolvingTacticSearchConfig {
  mode: 'bm25' | 'hybrid' | null;
  bm25: SelfEvolvingTacticBm25Config;
  embeddings: SelfEvolvingTacticEmbeddingsConfig;
  personalization: SelfEvolvingToggleConfig;
  negativeMemory: SelfEvolvingToggleConfig;
  queryExpansion: SelfEvolvingTacticQueryExpansionConfig;
  advisoryCount: number | null;
}

export interface SelfEvolvingTacticQueryExpansionConfig {
  enabled: boolean | null;
  tier: string | null;
}

export interface SelfEvolvingTacticBm25Config {
  enabled: boolean | null;
}

export interface SelfEvolvingTacticEmbeddingsConfig {
  enabled: boolean | null;
  provider: string | null;
  baseUrl: string | null;
  apiKey: string | null;
  apiKeyPresent?: boolean;
  model: string | null;
  dimensions: number | null;
  batchSize: number | null;
  timeoutMs: number | null;
  autoFallbackToBm25: boolean | null;
  local: SelfEvolvingTacticEmbeddingsLocalConfig;
}

export interface SelfEvolvingTacticEmbeddingsLocalConfig {
  autoInstall: boolean | null;
  pullOnStart: boolean | null;
  requireHealthyRuntime: boolean | null;
  failOpen: boolean | null;
}

export interface SelfEvolvingToggleConfig {
  enabled: boolean | null;
}

export interface SelfEvolvingPromotionConfig {
  mode: 'approval_gate' | 'auto_accept' | null;
  allowAutoAccept: boolean | null;
  shadowRequired: boolean | null;
  canaryRequired: boolean | null;
  hiveApprovalPreferred: boolean | null;
}

export interface SelfEvolvingBenchmarkConfig {
  enabled: boolean | null;
  harvestProductionRuns: boolean | null;
  autoCreateRegressionCases: boolean | null;
}

export interface SelfEvolvingHiveConfig {
  publishInspectionProjection: boolean | null;
  readonlyInspection: boolean | null;
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

export interface McpCatalogEntry {
  name: string;
  description: string | null;
  command: string;
  env: Record<string, string>;
  startupTimeoutSeconds: number | null;
  idleTimeoutMinutes: number | null;
  enabled: boolean | null;
}

export interface McpConfig {
  enabled: boolean | null;
  defaultStartupTimeout: number | null;
  defaultIdleTimeout: number | null;
  catalog: McpCatalogEntry[];
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
  const payload = buildLlmProviderPayload(config);
  const { data } = await client.post<RuntimeConfigUiRecord>(
    `/settings/runtime/llm/providers/${name}`,
    payload,
    withSettingsSectionTelemetry('llm-providers'),
  );
  return toUiRuntimeConfig(data);
}

export async function updateLlmProvider(name: string, config: LlmProviderConfig): Promise<RuntimeConfig> {
  const payload = buildLlmProviderPayload(config);
  const { data } = await client.put<RuntimeConfigUiRecord>(
    `/settings/runtime/llm/providers/${name}`,
    payload,
    withSettingsSectionTelemetry('llm-providers'),
  );
  return toUiRuntimeConfig(data);
}

export async function removeLlmProvider(name: string): Promise<void> {
  await client.delete(`/settings/runtime/llm/providers/${name}`);
}

export async function addLlmProviderAndImport(name: string, config: LlmProviderConfig): Promise<LlmProviderImportResult> {
  const { data } = await client.post<LlmProviderImportResult>(
    `/settings/runtime/llm/providers/${name}/import-models`,
    buildLlmProviderPayload(config),
  );
  return data;
}

export async function testSavedLlmProvider(name: string): Promise<LlmProviderTestResult> {
  const { data } = await client.post<LlmProviderTestResult>('/settings/runtime/llm/providers/test', {
    mode: 'saved',
    providerName: name,
    config: null,
  });
  return data;
}

export async function testDraftLlmProvider(name: string, config: LlmProviderConfig): Promise<LlmProviderTestResult> {
  const { data } = await client.post<LlmProviderTestResult>('/settings/runtime/llm/providers/test', {
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
}): Promise<RuntimeConfig> {
  const { data } = await client.put<RuntimeConfigUiRecord>(
    '/settings/runtime/advanced',
    config,
    withSettingsSectionTelemetry('advanced'),
  );
  return toUiRuntimeConfig(data);
}

function toNullableString(value: unknown): string | null {
  if (typeof value !== 'string') {
    return null;
  }
  const normalized = value.trim();
  return normalized.length > 0 ? normalized : null;
}

export function modelReferenceToSpec(model: ModelReference | null | undefined): string | null {
  if (model == null) {
    return null;
  }
  const id = toNullableString(model.id);
  if (id == null) {
    return null;
  }
  const provider = toNullableString(model.provider);
  if (provider == null || id.startsWith(`${provider}/`)) {
    return id;
  }
  return `${provider}/${id}`;
}

export function modelReferenceFromSpec(
  modelSpec: string | null | undefined,
  providerHint: string | null = null,
): ModelReference | null {
  const normalizedModelSpec = toNullableString(modelSpec);
  if (normalizedModelSpec == null) {
    return null;
  }
  const normalizedProvider = toNullableString(providerHint);
  if (normalizedProvider != null && normalizedModelSpec.startsWith(`${normalizedProvider}/`)) {
    return {
      provider: normalizedProvider,
      id: normalizedModelSpec.slice(normalizedProvider.length + 1),
    };
  }
  return {
    provider: normalizedProvider,
    id: normalizedModelSpec,
  };
}

function toModelRegistryConfig(value: unknown): ModelRegistryConfig {
  const record = value != null && typeof value === 'object' ? value as UnknownRecord : {};
  return {
    repositoryUrl: toNullableString(record.repositoryUrl),
    branch: toNullableString(record.branch) ?? 'main',
  };
}

function toStringArray(value: unknown): string[] {
  if (!Array.isArray(value)) {
    return [];
  }
  return value
    .filter((entry): entry is string => typeof entry === 'string')
    .map((entry) => entry.trim())
    .filter((entry) => entry.length > 0);
}

function normalizeSelfEvolvingJudgeTier(
  value: unknown,
  fallback: ExplicitModelTierId,
): ExplicitModelTierId {
  if (typeof value !== 'string') {
    return fallback;
  }
  const normalized = value.trim().toLowerCase();
  if (normalized === 'standard') {
    return 'smart';
  }
  if (normalized === 'premium') {
    return 'deep';
  }
  return isExplicitModelTier(normalized) ? normalized : fallback;
}

function toSelfEvolvingConfig(value: unknown): SelfEvolvingConfig {
  const record = value != null && typeof value === 'object' ? value as UnknownRecord : {};
  const capture = record.capture != null && typeof record.capture === 'object'
    ? record.capture as UnknownRecord
    : {};
  const judge = record.judge != null && typeof record.judge === 'object'
    ? record.judge as UnknownRecord
    : {};
  const evolution = record.evolution != null && typeof record.evolution === 'object'
    ? record.evolution as UnknownRecord
    : {};
  const tactics = record.tactics != null && typeof record.tactics === 'object'
    ? record.tactics as UnknownRecord
    : {};
  const tacticsSearch = tactics.search != null && typeof tactics.search === 'object'
    ? tactics.search as UnknownRecord
    : {};
  const tacticsBm25 = tacticsSearch.bm25 != null && typeof tacticsSearch.bm25 === 'object'
    ? tacticsSearch.bm25 as UnknownRecord
    : {};
  const tacticsEmbeddings = tacticsSearch.embeddings != null && typeof tacticsSearch.embeddings === 'object'
    ? tacticsSearch.embeddings as UnknownRecord
    : {};
  const tacticsEmbeddingsLocal = tacticsEmbeddings.local != null && typeof tacticsEmbeddings.local === 'object'
    ? tacticsEmbeddings.local as UnknownRecord
    : {};
  const tacticsPersonalization = tacticsSearch.personalization != null && typeof tacticsSearch.personalization === 'object'
    ? tacticsSearch.personalization as UnknownRecord
    : {};
  const tacticsNegativeMemory = tacticsSearch.negativeMemory != null && typeof tacticsSearch.negativeMemory === 'object'
    ? tacticsSearch.negativeMemory as UnknownRecord
    : {};
  const tacticsQueryExpansion = tacticsSearch.queryExpansion != null && typeof tacticsSearch.queryExpansion === 'object'
    ? tacticsSearch.queryExpansion as UnknownRecord
    : {};
  const promotion = record.promotion != null && typeof record.promotion === 'object'
    ? record.promotion as UnknownRecord
    : {};
  const benchmark = record.benchmark != null && typeof record.benchmark === 'object'
    ? record.benchmark as UnknownRecord
    : {};
  const hive = record.hive != null && typeof record.hive === 'object'
    ? record.hive as UnknownRecord
    : {};

  const selfEvolvingEnabled = typeof record.enabled === 'boolean' ? record.enabled : false;
  const tacticSearchMode = (toNullableString(tacticsSearch.mode) as 'bm25' | 'hybrid' | null) ?? 'hybrid';
  const tacticEmbeddingsProvider = normalizeSelfEvolvingEmbeddingProvider(
    toNullableString(tacticsEmbeddings.provider),
    tacticSearchMode,
  );
  const tacticEmbeddingsModel = normalizeSelfEvolvingEmbeddingModel(
    toNullableString(tacticsEmbeddings.model),
    tacticEmbeddingsProvider,
  );

  return {
    enabled: selfEvolvingEnabled,
    tracePayloadOverride: typeof record.tracePayloadOverride === 'boolean' ? record.tracePayloadOverride : true,
    managedByProperties: typeof record.managedByProperties === 'boolean' ? record.managedByProperties : false,
    overriddenPaths: toStringArray(record.overriddenPaths),
    capture: {
      llm: toNullableString(capture.llm) ?? 'full',
      tool: toNullableString(capture.tool) ?? 'full',
      context: toNullableString(capture.context) ?? 'full',
      skill: toNullableString(capture.skill) ?? 'full',
      tier: toNullableString(capture.tier) ?? 'full',
      infra: toNullableString(capture.infra) ?? 'meta_only',
    },
    judge: {
      enabled: typeof judge.enabled === 'boolean' ? judge.enabled : true,
      primaryTier: normalizeSelfEvolvingJudgeTier(judge.primaryTier, 'smart'),
      tiebreakerTier: normalizeSelfEvolvingJudgeTier(judge.tiebreakerTier, 'deep'),
      evolutionTier: normalizeSelfEvolvingJudgeTier(judge.evolutionTier, 'deep'),
      requireEvidenceAnchors: typeof judge.requireEvidenceAnchors === 'boolean' ? judge.requireEvidenceAnchors : true,
      uncertaintyThreshold: typeof judge.uncertaintyThreshold === 'number' ? judge.uncertaintyThreshold : 0.22,
    },
    evolution: {
      enabled: typeof evolution.enabled === 'boolean' ? evolution.enabled : true,
      modes: toStringArray(evolution.modes),
      artifactTypes: toStringArray(evolution.artifactTypes),
    },
    tactics: {
      enabled: selfEvolvingEnabled,
      search: {
        mode: tacticSearchMode,
        bm25: {
          enabled: typeof tacticsBm25.enabled === 'boolean' ? tacticsBm25.enabled : true,
        },
        embeddings: {
          enabled: tacticSearchMode === 'hybrid',
          provider: tacticEmbeddingsProvider,
          baseUrl: toNullableString(tacticsEmbeddings.baseUrl),
          apiKey: typeof tacticsEmbeddings.apiKey === 'string'
            ? toNullableString(tacticsEmbeddings.apiKey)
            : null,
          apiKeyPresent: typeof tacticsEmbeddings.apiKey === 'string'
            ? (typeof tacticsEmbeddings.apiKeyPresent === 'boolean' ? tacticsEmbeddings.apiKeyPresent : undefined)
            : hasSecretValue(tacticsEmbeddings.apiKey),
          model: tacticEmbeddingsModel,
          dimensions: typeof tacticsEmbeddings.dimensions === 'number' ? tacticsEmbeddings.dimensions : null,
          batchSize: typeof tacticsEmbeddings.batchSize === 'number' ? tacticsEmbeddings.batchSize : null,
          timeoutMs: typeof tacticsEmbeddings.timeoutMs === 'number' ? tacticsEmbeddings.timeoutMs : null,
          autoFallbackToBm25: typeof tacticsEmbeddings.autoFallbackToBm25 === 'boolean'
            ? tacticsEmbeddings.autoFallbackToBm25
            : true,
          local: {
            autoInstall: typeof tacticsEmbeddingsLocal.autoInstall === 'boolean'
              ? tacticsEmbeddingsLocal.autoInstall
              : false,
            pullOnStart: typeof tacticsEmbeddingsLocal.pullOnStart === 'boolean'
              ? tacticsEmbeddingsLocal.pullOnStart
              : false,
            requireHealthyRuntime: typeof tacticsEmbeddingsLocal.requireHealthyRuntime === 'boolean'
              ? tacticsEmbeddingsLocal.requireHealthyRuntime
              : true,
            failOpen: typeof tacticsEmbeddingsLocal.failOpen === 'boolean'
              ? tacticsEmbeddingsLocal.failOpen
              : true,
          },
        },
        personalization: {
          enabled: typeof tacticsPersonalization.enabled === 'boolean' ? tacticsPersonalization.enabled : true,
        },
        negativeMemory: {
          enabled: typeof tacticsNegativeMemory.enabled === 'boolean' ? tacticsNegativeMemory.enabled : true,
        },
        queryExpansion: {
          enabled: typeof tacticsQueryExpansion.enabled === 'boolean'
            ? tacticsQueryExpansion.enabled
            : true,
          tier: typeof tacticsQueryExpansion.tier === 'string'
            ? tacticsQueryExpansion.tier
            : 'balanced',
        },
        advisoryCount: typeof tacticsSearch.advisoryCount === 'number'
          ? tacticsSearch.advisoryCount
          : 1,
      },
    },
    promotion: {
      mode: toNullableString(promotion.mode) as 'approval_gate' | 'auto_accept' | null ?? 'approval_gate',
      allowAutoAccept: typeof promotion.allowAutoAccept === 'boolean' ? promotion.allowAutoAccept : true,
      shadowRequired: typeof promotion.shadowRequired === 'boolean' ? promotion.shadowRequired : true,
      canaryRequired: typeof promotion.canaryRequired === 'boolean' ? promotion.canaryRequired : true,
      hiveApprovalPreferred: typeof promotion.hiveApprovalPreferred === 'boolean' ? promotion.hiveApprovalPreferred : true,
    },
    benchmark: {
      enabled: typeof benchmark.enabled === 'boolean' ? benchmark.enabled : true,
      harvestProductionRuns: typeof benchmark.harvestProductionRuns === 'boolean' ? benchmark.harvestProductionRuns : true,
      autoCreateRegressionCases: typeof benchmark.autoCreateRegressionCases === 'boolean' ? benchmark.autoCreateRegressionCases : true,
    },
    hive: {
      publishInspectionProjection: typeof hive.publishInspectionProjection === 'boolean'
        ? hive.publishInspectionProjection
        : true,
      readonlyInspection: typeof hive.readonlyInspection === 'boolean' ? hive.readonlyInspection : true,
    },
  };
}

function normalizeSelfEvolvingEmbeddingProvider(
  provider: string | null,
  mode: 'bm25' | 'hybrid',
): string | null {
  if (provider != null && provider.length > 0) {
    return provider.trim().toLowerCase();
  }
  return mode === 'hybrid' ? DEFAULT_SELF_EVOLVING_TACTIC_EMBEDDING_PROVIDER : null;
}

function normalizeSelfEvolvingEmbeddingModel(
  model: string | null,
  provider: string | null,
): string | null {
  if (model != null && model.length > 0) {
    return model;
  }
  return provider === DEFAULT_SELF_EVOLVING_TACTIC_EMBEDDING_PROVIDER
    ? DEFAULT_SELF_EVOLVING_TACTIC_LOCAL_MODEL
    : null;
}

function toModelReference(value: unknown): ModelReference | null {
  if (typeof value === 'string') {
    return modelReferenceFromSpec(value);
  }
  if (value == null || typeof value !== 'object') {
    return null;
  }
  const record = value as UnknownRecord;
  const provider = toNullableString(record.provider);
  const id = toNullableString(record.id);
  if (provider == null && id == null) {
    return null;
  }
  return {
    provider,
    id,
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
    model: toModelReference(record.model),
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
