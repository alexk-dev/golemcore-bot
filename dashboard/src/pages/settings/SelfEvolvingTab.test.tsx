import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';

import type { SelfEvolvingConfig } from '../../api/settings';
import type { SelfEvolvingTacticSearchStatus } from '../../api/selfEvolving';
import SelfEvolvingTab from './SelfEvolvingTab';

vi.mock('../../hooks/useSettings', () => ({
  useUpdateRuntimeConfig: () => ({
    mutateAsync: vi.fn(() => Promise.resolve()),
    isPending: false,
  }),
}));

const config: SelfEvolvingConfig = {
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
    modes: ['fix', 'derive', 'tune'],
    artifactTypes: ['skill', 'prompt', 'routing_policy'],
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
        provider: 'ollama',
        baseUrl: 'http://localhost:11434',
        apiKey: 'secret-key',
        model: 'qwen3-embedding:0.6b',
        dimensions: 1024,
        batchSize: 16,
        timeoutMs: 5000,
        autoFallbackToBm25: true,
        local: {
          autoInstall: true,
          pullOnStart: true,
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
    mode: 'approval_gate' as const,
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
  managedByProperties: true,
  overriddenPaths: ['enabled', 'tactics.search.mode'],
};

const tacticSearchStatus: SelfEvolvingTacticSearchStatus = {
  mode: 'hybrid',
  reason: 'Embedding model qwen3-embedding:0.6b is not installed in Ollama',
  provider: 'ollama',
  model: 'qwen3-embedding:0.6b',
  degraded: true,
  runtimeInstalled: true,
  runtimeHealthy: true,
  runtimeVersion: '0.19.0',
  baseUrl: 'http://127.0.0.1:11434',
  modelAvailable: false,
  autoInstallConfigured: true,
  pullOnStartConfigured: true,
  pullAttempted: true,
  pullSucceeded: false,
  updatedAt: '2026-04-01T23:30:00Z',
};

describe('SelfEvolvingTab', () => {
  it('renders judge tiers and simplified tactic embedding controls', () => {
    const html = renderToStaticMarkup(
      <SelfEvolvingTab
        config={config}
        tacticSearchStatus={tacticSearchStatus}
        onInstallTacticEmbedding={vi.fn(() => Promise.resolve())}
        onSave={vi.fn(() => Promise.resolve())}
      />,
    );

    expect(html).toContain('General');
    expect(html).toContain('Judge');
    expect(html).toContain('Tactics');
    expect(html).toContain('Promotion');
    expect(html).toContain('Primary judge tier');
    expect(html).toContain('Tiebreaker tier');
    expect(html).toContain('Promotion mode');
    expect(html).toContain('Smart');
    expect(html).toContain('Deep');
    expect(html).not.toContain('Standard');
    expect(html).not.toContain('Premium');
    expect(html).toContain('Tactic search embeddings');
    expect(html).toContain('Local embedding model');
    expect(html).toContain('Embedding model');
    expect(html).toContain('Install model');
    expect(html).toContain('Used for tactic indexing and hybrid retrieval.');
    expect(html).toContain('bot.self-evolving.bootstrap');
    expect(html).toContain('Some Self-Evolving settings are managed by');
    expect(html).toContain('enabled');
    expect(html).toContain('qwen3-embedding:0.6b');
    expect(html).toContain('Model status');
    expect(html).toContain('Ollama is ready, but the selected embedding model is not installed yet.');
    expect(html).not.toContain('Local embedding runtime status');
    expect(html).not.toContain('Auto-install local model');
    expect(html).not.toContain('Trace payload override');
    expect(html).not.toContain('BM25 fallback');
    expect(html).not.toContain('Enable tactic retrieval');
    expect(html).not.toContain('Enable embeddings');
    expect(html).toContain('disabled=""');
  });
});
