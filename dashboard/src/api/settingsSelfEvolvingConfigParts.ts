import { isExplicitModelTier, type ExplicitModelTierId } from '../lib/modelTiers';
import type {
  SelfEvolvingBenchmarkConfig,
  SelfEvolvingHiveConfig,
  SelfEvolvingPromotionConfig,
  SelfEvolvingTacticEmbeddingsConfig,
} from './settingsTypes';
import { hasSecretValue, toNullableString, type UnknownRecord } from './settingsUtils';

const DEFAULT_SELF_EVOLVING_TACTIC_EMBEDDING_PROVIDER = 'ollama';
const DEFAULT_SELF_EVOLVING_TACTIC_LOCAL_MODEL = 'qwen3-embedding:0.6b';


export function normalizeSelfEvolvingJudgeTier(value: unknown, fallback: ExplicitModelTierId): ExplicitModelTierId {
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

export function normalizeSelfEvolvingEmbeddingProvider(provider: string | null, mode: 'bm25' | 'hybrid'): string | null {
  if (provider != null && provider.length > 0) {
    return provider.trim().toLowerCase();
  }
  return mode === 'hybrid' ? DEFAULT_SELF_EVOLVING_TACTIC_EMBEDDING_PROVIDER : null;
}

function normalizeSelfEvolvingEmbeddingModel(model: string | null, provider: string | null): string | null {
  if (model != null && model.length > 0) {
    return model;
  }
  return provider === DEFAULT_SELF_EVOLVING_TACTIC_EMBEDDING_PROVIDER ? DEFAULT_SELF_EVOLVING_TACTIC_LOCAL_MODEL : null;
}

export function buildTacticEmbeddingsConfig(
  embeddings: UnknownRecord,
  local: UnknownRecord,
  mode: 'bm25' | 'hybrid',
): SelfEvolvingTacticEmbeddingsConfig {
  const provider = normalizeSelfEvolvingEmbeddingProvider(toNullableString(embeddings.provider), mode);
  return {
    enabled: mode === 'hybrid',
    provider,
    baseUrl: toNullableString(embeddings.baseUrl),
    apiKey: typeof embeddings.apiKey === 'string' ? toNullableString(embeddings.apiKey) : null,
    apiKeyPresent: typeof embeddings.apiKey === 'string'
      ? (typeof embeddings.apiKeyPresent === 'boolean' ? embeddings.apiKeyPresent : undefined)
      : hasSecretValue(embeddings.apiKey),
    model: normalizeSelfEvolvingEmbeddingModel(toNullableString(embeddings.model), provider),
    dimensions: typeof embeddings.dimensions === 'number' ? embeddings.dimensions : null,
    batchSize: typeof embeddings.batchSize === 'number' ? embeddings.batchSize : null,
    timeoutMs: typeof embeddings.timeoutMs === 'number' ? embeddings.timeoutMs : null,
    autoFallbackToBm25: typeof embeddings.autoFallbackToBm25 === 'boolean' ? embeddings.autoFallbackToBm25 : true,
    local: {
      autoInstall: typeof local.autoInstall === 'boolean' ? local.autoInstall : false,
      pullOnStart: typeof local.pullOnStart === 'boolean' ? local.pullOnStart : false,
      requireHealthyRuntime: typeof local.requireHealthyRuntime === 'boolean' ? local.requireHealthyRuntime : true,
      failOpen: typeof local.failOpen === 'boolean' ? local.failOpen : true,
    },
  };
}

export function buildPromotionConfig(promotion: UnknownRecord): SelfEvolvingPromotionConfig {
  return {
    mode: toNullableString(promotion.mode) as 'approval_gate' | 'auto_accept' | null ?? 'approval_gate',
    allowAutoAccept: typeof promotion.allowAutoAccept === 'boolean' ? promotion.allowAutoAccept : true,
    shadowRequired: typeof promotion.shadowRequired === 'boolean' ? promotion.shadowRequired : true,
    canaryRequired: typeof promotion.canaryRequired === 'boolean' ? promotion.canaryRequired : true,
    hiveApprovalPreferred: typeof promotion.hiveApprovalPreferred === 'boolean' ? promotion.hiveApprovalPreferred : true,
  };
}

export function buildBenchmarkConfig(benchmark: UnknownRecord): SelfEvolvingBenchmarkConfig {
  return {
    enabled: typeof benchmark.enabled === 'boolean' ? benchmark.enabled : true,
    harvestProductionRuns: typeof benchmark.harvestProductionRuns === 'boolean' ? benchmark.harvestProductionRuns : true,
    autoCreateRegressionCases: typeof benchmark.autoCreateRegressionCases === 'boolean' ? benchmark.autoCreateRegressionCases : true,
  };
}

export function buildHiveConfig(hive: UnknownRecord): SelfEvolvingHiveConfig {
  return {
    publishInspectionProjection: typeof hive.publishInspectionProjection === 'boolean' ? hive.publishInspectionProjection : true,
    readonlyInspection: typeof hive.readonlyInspection === 'boolean' ? hive.readonlyInspection : true,
  };
}


export function readBoolean(record: UnknownRecord, key: string, fallback: boolean): boolean {
  const value = record[key];
  return typeof value === 'boolean' ? value : fallback;
}

export function readNumber(record: UnknownRecord, key: string, fallback: number | null): number | null {
  const value = record[key];
  return typeof value === 'number' ? value : fallback;
}
