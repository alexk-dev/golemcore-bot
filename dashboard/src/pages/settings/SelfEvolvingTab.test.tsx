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
};

const tacticSearchStatus: SelfEvolvingTacticSearchStatus = {
  mode: 'hybrid',
  reason: 'local embedding model unavailable',
  provider: 'ollama',
  model: 'qwen3-embedding:0.6b',
  degraded: true,
  runtimeHealthy: false,
  modelAvailable: false,
  autoInstallConfigured: true,
  pullOnStartConfigured: true,
  pullAttempted: true,
  pullSucceeded: false,
  updatedAt: '2026-04-01T23:30:00Z',
};

describe('SelfEvolvingTab', () => {
  it('renders judge tiers, tactic embeddings settings, and readonly local runtime status', () => {
    const html = renderToStaticMarkup(
      <SelfEvolvingTab
        config={config}
        tacticSearchStatus={tacticSearchStatus}
        onSave={vi.fn(() => Promise.resolve())}
      />,
    );

    expect(html).toContain('Primary judge tier');
    expect(html).toContain('Tiebreaker tier');
    expect(html).toContain('Promotion mode');
    expect(html).toContain('Trace payload override');
    expect(html).toContain('Smart');
    expect(html).toContain('Deep');
    expect(html).not.toContain('Standard');
    expect(html).not.toContain('Premium');
    expect(html).toContain('Tactic search embeddings');
    expect(html).toContain('Embedding provider');
    expect(html).toContain('Embedding model');
    expect(html).toContain('Auto-install local model');
    expect(html).toContain('Require healthy runtime');
    expect(html).toContain('bot.self-evolving.bootstrap');
    expect(html).toContain('BM25 fallback');
    expect(html).toContain('Local embedding runtime status');
    expect(html).toContain('qwen3-embedding:0.6b');
    expect(html).toContain('Runtime healthy');
    expect(html).toContain('Model available');
    expect(html).toContain('Pull attempted');
  });
});
