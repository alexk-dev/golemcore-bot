import type { SelfEvolvingConfig } from './settingsTypes';
import {
  buildBenchmarkConfig,
  buildHiveConfig,
  buildPromotionConfig,
  buildTacticEmbeddingsConfig,
  normalizeSelfEvolvingJudgeTier,
  readBoolean,
  readNumber,
} from './settingsSelfEvolvingConfigParts';
import { toNullableString, toStringArray, type UnknownRecord } from './settingsUtils';

function readRecord(value: unknown): UnknownRecord {
  return value != null && typeof value === 'object' ? value as UnknownRecord : {};
}

export function toSelfEvolvingConfig(value: unknown): SelfEvolvingConfig {
  const record = readRecord(value);
  const capture = readRecord(record.capture);
  const judge = readRecord(record.judge);
  const evolution = readRecord(record.evolution);
  const tactics = readRecord(record.tactics);
  const tacticsSearch = readRecord(tactics.search);
  const tacticsBm25 = readRecord(tacticsSearch.bm25);
  const tacticsEmbeddings = readRecord(tacticsSearch.embeddings);
  const tacticsEmbeddingsLocal = readRecord(tacticsEmbeddings.local);
  const tacticsPersonalization = readRecord(tacticsSearch.personalization);
  const tacticsNegativeMemory = readRecord(tacticsSearch.negativeMemory);
  const tacticsQueryExpansion = readRecord(tacticsSearch.queryExpansion);
  const promotion = readRecord(record.promotion);
  const benchmark = readRecord(record.benchmark);
  const hive = readRecord(record.hive);
  const selfEvolvingEnabled = readBoolean(record, 'enabled', false);
  const tacticSearchMode = (toNullableString(tacticsSearch.mode) as 'bm25' | 'hybrid' | null) ?? 'hybrid';
  return {
    enabled: selfEvolvingEnabled,
    tracePayloadOverride: readBoolean(record, 'tracePayloadOverride', true),
    managedByProperties: readBoolean(record, 'managedByProperties', false),
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
      enabled: readBoolean(judge, 'enabled', true),
      primaryTier: normalizeSelfEvolvingJudgeTier(judge.primaryTier, 'smart'),
      tiebreakerTier: normalizeSelfEvolvingJudgeTier(judge.tiebreakerTier, 'deep'),
      evolutionTier: normalizeSelfEvolvingJudgeTier(judge.evolutionTier, 'deep'),
      requireEvidenceAnchors: readBoolean(judge, 'requireEvidenceAnchors', true),
      uncertaintyThreshold: readNumber(judge, 'uncertaintyThreshold', 0.22),
    },
    evolution: { enabled: readBoolean(evolution, 'enabled', true), modes: toStringArray(evolution.modes), artifactTypes: toStringArray(evolution.artifactTypes) },
    tactics: {
      enabled: selfEvolvingEnabled,
      search: {
        mode: tacticSearchMode,
        bm25: { enabled: readBoolean(tacticsBm25, 'enabled', true) },
        embeddings: buildTacticEmbeddingsConfig(tacticsEmbeddings, tacticsEmbeddingsLocal, tacticSearchMode),
        personalization: { enabled: readBoolean(tacticsPersonalization, 'enabled', true) },
        negativeMemory: { enabled: readBoolean(tacticsNegativeMemory, 'enabled', true) },
        queryExpansion: { enabled: readBoolean(tacticsQueryExpansion, 'enabled', true), tier: typeof tacticsQueryExpansion.tier === 'string' ? tacticsQueryExpansion.tier : 'balanced' },
        advisoryCount: readNumber(tacticsSearch, 'advisoryCount', 1),
      },
    },
    promotion: buildPromotionConfig(promotion),
    benchmark: buildBenchmarkConfig(benchmark),
    hive: buildHiveConfig(hive),
  };
}
