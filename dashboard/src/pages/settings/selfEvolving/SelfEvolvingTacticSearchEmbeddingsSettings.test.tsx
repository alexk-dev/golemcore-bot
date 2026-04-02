import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';

import type { SelfEvolvingConfig } from '../../../api/settings';
import { SelfEvolvingTacticSearchEmbeddingsSettings } from './SelfEvolvingTacticSearchEmbeddingsSettings';

function buildConfig(provider: string | null, model: string | null): SelfEvolvingConfig {
  return {
    enabled: true,
    tracePayloadOverride: true,
    capture: {
      llm: 'full',
      tool: 'full',
      context: 'full',
      skill: 'full',
      tier: 'full',
      infra: 'meta_only',
    },
    judge: {
      enabled: true,
      primaryTier: 'smart',
      tiebreakerTier: 'deep',
      evolutionTier: 'deep',
      requireEvidenceAnchors: true,
      uncertaintyThreshold: 0.22,
    },
    evolution: {
      enabled: true,
      modes: ['fix'],
      artifactTypes: ['skill'],
    },
    tactics: {
      enabled: true,
      search: {
        mode: 'hybrid',
        bm25: {
          enabled: true,
        },
        embeddings: {
          enabled: true,
          provider,
          baseUrl: null,
          apiKey: null,
          model,
          dimensions: null,
          batchSize: null,
          timeoutMs: 5000,
          autoFallbackToBm25: true,
          local: {
            autoInstall: true,
            pullOnStart: false,
            requireHealthyRuntime: true,
            failOpen: true,
          },
        },
        rerank: {
          crossEncoder: true,
          tier: 'deep',
          timeoutMs: 5000,
        },
        personalization: {
          enabled: true,
        },
        negativeMemory: {
          enabled: true,
        },
      },
    },
    promotion: {
      mode: 'approval_gate',
      allowAutoAccept: true,
      shadowRequired: true,
      canaryRequired: true,
      hiveApprovalPreferred: true,
    },
    benchmark: {
      enabled: true,
      harvestProductionRuns: true,
      autoCreateRegressionCases: true,
    },
    hive: {
      publishInspectionProjection: true,
      readonlyInspection: true,
    },
  };
}

describe('SelfEvolvingTacticSearchEmbeddingsSettings', () => {
  it('renders ollama preset options and hides remote-only fields', () => {
    const html = renderToStaticMarkup(
      <SelfEvolvingTacticSearchEmbeddingsSettings
        form={buildConfig('ollama', null)}
        setForm={vi.fn()}
      />,
    );

    expect(html).toContain('Qwen3 Embedding 0.6B');
    expect(html).toContain('Nomic Embed Text');
    expect(html).toContain('MXBAI Embed Large');
    expect(html).toContain('BGE-M3');
    expect(html).not.toContain('Base URL');
    expect(html).not.toContain('Embedding API key');
    expect(html).toContain('value="1024"');
    expect(html).toContain('value="32"');
  });

  it('keeps remote provider fields visible for openai-compatible embeddings', () => {
    const html = renderToStaticMarkup(
      <SelfEvolvingTacticSearchEmbeddingsSettings
        form={buildConfig('openai_compatible', 'text-embedding-3-large')}
        setForm={vi.fn()}
      />,
    );

    expect(html).toContain('Base URL');
    expect(html).toContain('Embedding API key');
  });
});
