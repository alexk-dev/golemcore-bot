import type { ExplicitModelTierId } from '../lib/modelTiers';

export type ApiType = 'openai' | 'anthropic' | 'gemini';

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

export interface ModelRegistryConfig { repositoryUrl: string | null; branch: string | null; }
export interface LlmConfig { providers: Record<string, LlmProviderConfig>; }
export interface LlmProviderConfig { apiKey: string | null; apiKeyPresent?: boolean; baseUrl: string | null; requestTimeoutSeconds: number | null; apiType: ApiType | null; legacyApi: boolean | null; }
export interface LlmProviderImportResult { providerSaved: boolean; providerName: string; resolvedEndpoint: string | null; addedModels: string[]; skippedModels: string[]; errors: string[]; }
export type LlmProviderTestMode = 'saved' | 'draft';
export interface LlmProviderTestResult { mode: LlmProviderTestMode; providerName: string; resolvedEndpoint: string | null; models: string[]; success: boolean; error: string | null; }
export interface MemoryConfig { enabled: boolean | null; softPromptBudgetTokens: number | null; maxPromptBudgetTokens: number | null; workingTopK: number | null; episodicTopK: number | null; semanticTopK: number | null; proceduralTopK: number | null; promotionEnabled: boolean | null; promotionMinConfidence: number | null; decayEnabled: boolean | null; decayDays: number | null; retrievalLookbackDays: number | null; codeAwareExtractionEnabled: boolean | null; disclosure?: MemoryDisclosureConfig | null; diagnostics?: MemoryDiagnosticsConfig | null; }
export interface MemoryPreset { id: string; label: string; comment: string; memory: MemoryConfig; }
export type MemoryDisclosureMode = 'index' | 'summary' | 'selective_detail' | 'full_pack';
export type MemoryPromptStyle = 'compact' | 'balanced' | 'rich';
export type MemoryDiagnosticsVerbosity = 'off' | 'basic' | 'detailed';
export interface MemoryDisclosureConfig { mode: MemoryDisclosureMode | null; promptStyle: MemoryPromptStyle | null; toolExpansionEnabled: boolean | null; disclosureHintsEnabled: boolean | null; detailMinScore: number | null; }
export interface MemoryDiagnosticsConfig { verbosity: MemoryDiagnosticsVerbosity | null; }
export interface SkillsConfig { enabled: boolean | null; progressiveLoading: boolean | null; marketplaceSourceType: 'repository' | 'directory' | 'sandbox' | null; marketplaceRepositoryDirectory: string | null; marketplaceSandboxPath: string | null; marketplaceRepositoryUrl: string | null; marketplaceBranch: string | null; }
export interface TurnConfig { maxLlmCalls: number | null; maxToolExecutions: number | null; deadline: string | null; progressUpdatesEnabled: boolean | null; progressIntentEnabled: boolean | null; progressBatchSize: number | null; progressMaxSilenceSeconds: number | null; progressSummaryTimeoutMs: number | null; }
export interface TelegramConfig { enabled: boolean | null; token: string | null; tokenPresent?: boolean; authMode: 'invite_only' | null; allowedUsers: string[]; inviteCodes: InviteCode[]; }
export interface InviteCode { code: string; used: boolean; createdAt: string; }
export interface ModelRouterConfig { routing: TierBinding; tiers: Record<ExplicitModelTierId, TierBinding>; dynamicTierEnabled: boolean | null; }
export interface ModelReference { provider: string | null; id: string | null; }
export type FallbackMode = 'sequential' | 'round_robin' | 'weighted';
export interface TierFallback { model: ModelReference | null; reasoning: string | null; temperature: number | null; weight: number | null; }
export interface TierBinding { model: ModelReference | null; reasoning: string | null; temperature: number | null; fallbackMode: FallbackMode; fallbacks: TierFallback[]; }
export interface ToolsConfig { filesystemEnabled: boolean | null; shellEnabled: boolean | null; skillManagementEnabled: boolean | null; skillTransitionEnabled: boolean | null; tierEnabled: boolean | null; goalManagementEnabled: boolean | null; shellEnvironmentVariables: ShellEnvironmentVariable[]; }
export interface ShellEnvironmentVariable { name: string; value: string; }
export interface VoiceConfig { enabled: boolean | null; apiKey: string | null; apiKeyPresent?: boolean; voiceId: string | null; ttsModelId: string | null; sttModelId: string | null; speed: number | null; telegramRespondWithVoice: boolean | null; telegramTranscribeIncoming: boolean | null; sttProvider: string | null; ttsProvider: string | null; whisperSttUrl: string | null; whisperSttApiKey: string | null; whisperSttApiKeyPresent?: boolean; }
export interface UsageConfig { enabled: boolean | null; }
export interface TelemetryConfig { enabled: boolean | null; clientId?: string; }
export interface PlanConfig { enabled: boolean | null; maxPlans: number | null; maxStepsPerPlan: number | null; stopOnFailure: boolean | null; }
export interface HiveConfig { enabled: boolean | null; serverUrl: string | null; displayName: string | null; hostLabel: string | null; dashboardBaseUrl: string | null; ssoEnabled: boolean | null; autoConnect: boolean | null; managedByProperties: boolean | null; sdlc: HiveSdlcConfig; }
export interface HiveSdlcConfig { currentContextEnabled: boolean | null; cardReadEnabled: boolean | null; cardSearchEnabled: boolean | null; threadMessageEnabled: boolean | null; reviewRequestEnabled: boolean | null; followupCardCreateEnabled: boolean | null; lifecycleSignalEnabled: boolean | null; }
export interface SelfEvolvingConfig { enabled: boolean | null; tracePayloadOverride: boolean | null; managedByProperties?: boolean | null; overriddenPaths?: string[]; capture: SelfEvolvingCaptureConfig; judge: SelfEvolvingJudgeConfig; evolution: SelfEvolvingEvolutionConfig; tactics: SelfEvolvingTacticsConfig; promotion: SelfEvolvingPromotionConfig; benchmark: SelfEvolvingBenchmarkConfig; hive: SelfEvolvingHiveConfig; }
export interface SelfEvolvingCaptureConfig { llm: string | null; tool: string | null; context: string | null; skill: string | null; tier: string | null; infra: string | null; }
export interface SelfEvolvingJudgeConfig { enabled: boolean | null; primaryTier: string | null; tiebreakerTier: string | null; evolutionTier: string | null; requireEvidenceAnchors: boolean | null; uncertaintyThreshold: number | null; }
export interface SelfEvolvingEvolutionConfig { enabled: boolean | null; modes: string[]; artifactTypes: string[]; }
export interface SelfEvolvingTacticsConfig { enabled: boolean | null; search: SelfEvolvingTacticSearchConfig; }
export interface SelfEvolvingTacticSearchConfig { mode: 'bm25' | 'hybrid' | null; bm25: SelfEvolvingTacticBm25Config; embeddings: SelfEvolvingTacticEmbeddingsConfig; personalization: SelfEvolvingToggleConfig; negativeMemory: SelfEvolvingToggleConfig; queryExpansion: SelfEvolvingTacticQueryExpansionConfig; advisoryCount: number | null; }
export interface SelfEvolvingTacticQueryExpansionConfig { enabled: boolean | null; tier: string | null; }
export interface SelfEvolvingTacticBm25Config { enabled: boolean | null; }
export interface SelfEvolvingTacticEmbeddingsConfig { enabled: boolean | null; provider: string | null; baseUrl: string | null; apiKey: string | null; apiKeyPresent?: boolean; model: string | null; dimensions: number | null; batchSize: number | null; timeoutMs: number | null; autoFallbackToBm25: boolean | null; local: SelfEvolvingTacticEmbeddingsLocalConfig; }
export interface SelfEvolvingTacticEmbeddingsLocalConfig { autoInstall: boolean | null; pullOnStart: boolean | null; requireHealthyRuntime: boolean | null; failOpen: boolean | null; }
export interface SelfEvolvingToggleConfig { enabled: boolean | null; }
export interface SelfEvolvingPromotionConfig { mode: 'approval_gate' | 'auto_accept' | null; allowAutoAccept: boolean | null; shadowRequired: boolean | null; canaryRequired: boolean | null; hiveApprovalPreferred: boolean | null; }
export interface SelfEvolvingBenchmarkConfig { enabled: boolean | null; harvestProductionRuns: boolean | null; autoCreateRegressionCases: boolean | null; }
export interface SelfEvolvingHiveConfig { publishInspectionProjection: boolean | null; readonlyInspection: boolean | null; }
export interface AutoModeConfig { enabled: boolean | null; tickIntervalSeconds: number | null; taskTimeLimitMinutes: number | null; autoStart: boolean | null; maxGoals: number | null; modelTier: string | null; reflectionEnabled: boolean | null; reflectionFailureThreshold: number | null; reflectionModelTier: string | null; reflectionTierPriority: boolean | null; notifyMilestones: boolean | null; }
export interface TracingConfig { enabled: boolean | null; payloadSnapshotsEnabled: boolean | null; sessionTraceBudgetMb: number | null; maxSnapshotSizeKb: number | null; maxSnapshotsPerSpan: number | null; maxTracesPerSession: number | null; captureInboundPayloads: boolean | null; captureOutboundPayloads: boolean | null; captureToolPayloads: boolean | null; captureLlmPayloads: boolean | null; }
export interface RateLimitConfig { enabled: boolean | null; userRequestsPerMinute: number | null; userRequestsPerHour: number | null; userRequestsPerDay: number | null; }
export interface SecurityConfig { sanitizeInput: boolean | null; detectPromptInjection: boolean | null; detectCommandInjection: boolean | null; maxInputLength: number | null; allowlistEnabled: boolean | null; toolConfirmationEnabled: boolean | null; toolConfirmationTimeoutSeconds: number | null; }
export interface McpCatalogEntry { name: string; description: string | null; command: string; env: Record<string, string>; startupTimeoutSeconds: number | null; idleTimeoutMinutes: number | null; enabled: boolean | null; }
export interface McpConfig { enabled: boolean | null; defaultStartupTimeout: number | null; defaultIdleTimeout: number | null; catalog: McpCatalogEntry[]; }
export interface CompactionConfig { enabled: boolean | null; triggerMode: 'model_ratio' | 'token_threshold' | null; modelThresholdRatio: number | null; maxContextTokens: number | null; keepLastMessages: number | null; }
