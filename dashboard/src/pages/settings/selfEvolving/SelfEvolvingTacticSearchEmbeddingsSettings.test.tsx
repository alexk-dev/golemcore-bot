import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';

import type { SelfEvolvingConfig } from '../../../api/settingsTypes';
import type { SelfEvolvingTacticSearchStatus } from '../../../api/selfEvolving';
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
        personalization: {
          enabled: true,
        },
        negativeMemory: {
          enabled: true,
        },
        queryExpansion: {
          enabled: true,
          tier: 'balanced',
        },
        advisoryCount: 1,
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
  const localStatus: SelfEvolvingTacticSearchStatus = {
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
    pullOnStartConfigured: false,
    pullAttempted: false,
    pullSucceeded: false,
    updatedAt: '2026-04-02T16:00:00Z',
  };

  it('puts local model selection and install action at the top for ollama', () => {
    const html = renderToStaticMarkup(
      <SelfEvolvingTacticSearchEmbeddingsSettings
        form={buildConfig('ollama', null)}
        setForm={vi.fn()}
        status={localStatus}
        isInstalling={false}
        onInstall={vi.fn()}
      />,
    );

    expect(html).toContain('Local embedding model');
    expect(html).toContain('Used for tactic indexing and hybrid retrieval.');
    expect(html).toContain('Install model');
    expect(html).toContain('Model status');
    expect(html).toContain('Missing');
    expect(html).toContain('Ollama is ready, but the selected embedding model is not installed yet.');
    expect(html).toContain('Install the selected model to enable local tactic indexing and hybrid retrieval.');
    expect(html).toContain('Qwen3 Embedding 0.6B');
    expect(html).toContain('Nomic Embed Text');
    expect(html).toContain('MXBAI Embed Large');
    expect(html).toContain('BGE-M3');
    expect(html).not.toContain('Base URL');
    expect(html).not.toContain('Embedding API key');
    expect(html).not.toContain('Local embedding runtime');
    expect(html).not.toContain('Auto-install local model');
    expect(html).toContain('value="1024"');
    expect(html).toContain('value="32"');
  });

  it('shows explicit missing-Ollama diagnostics instead of model install controls', () => {
    const html = renderToStaticMarkup(
      <SelfEvolvingTacticSearchEmbeddingsSettings
        form={buildConfig('ollama', null)}
        setForm={vi.fn()}
        status={{
          ...localStatus,
          reason: 'Ollama is not installed on this machine',
          runtimeInstalled: false,
          runtimeHealthy: false,
          runtimeVersion: null,
        }}
        isInstalling={false}
        onInstall={vi.fn()}
      />,
    );

    expect(html).toContain('Ollama is not installed on this machine.');
    expect(html).toContain('Install Ollama locally or use the latest base image that already bundles it.');
    expect(html).toContain('brew install ollama &amp;&amp; brew services start ollama');
    expect(html).toContain('Expected endpoint: http://127.0.0.1:11434');
    expect(html).toContain('Model install becomes available after Ollama is installed and running.');
    expect(html).not.toContain('Install model');
  });

  it('keeps stale diagnostics neutral until the live status refresh completes', () => {
    const html = renderToStaticMarkup(
      <SelfEvolvingTacticSearchEmbeddingsSettings
        form={buildConfig('ollama', null)}
        setForm={vi.fn()}
        status={{
          ...localStatus,
          mode: 'bm25',
          reason: 'selfevolving tactics disabled',
          runtimeState: 'disabled',
          runtimeInstalled: false,
          runtimeHealthy: false,
          runtimeVersion: null,
        }}
        isInstalling={false}
        onInstall={vi.fn()}
      />,
    );

    expect(html).toContain('Local embedding diagnostics will appear here after the next status refresh.');
    expect(html).toContain('Checking the selected model in the local runtime.');
    expect(html).not.toContain('Install model');
    expect(html).not.toContain('Ollama is not installed on this machine.');
    expect(html).not.toContain('Install Ollama locally or use the latest base image that already bundles it.');
  });

  it('keeps the UI in checking mode when the loaded status belongs to a different model', () => {
    const html = renderToStaticMarkup(
      <SelfEvolvingTacticSearchEmbeddingsSettings
        form={buildConfig('ollama', 'bge-m3')}
        setForm={vi.fn()}
        status={{
          ...localStatus,
          model: 'qwen3-embedding:0.6b',
          modelAvailable: true,
          degraded: false,
        }}
        isInstalling={false}
        onInstall={vi.fn()}
      />,
    );

    expect(html).toContain('Checking...');
    expect(html).toContain('Checking the selected model in the local runtime.');
    expect(html).not.toContain('Install model');
  });

  it('keeps remote provider fields visible for openai-compatible embeddings', () => {
    const html = renderToStaticMarkup(
      <SelfEvolvingTacticSearchEmbeddingsSettings
        form={buildConfig('openai_compatible', 'text-embedding-3-large')}
        setForm={vi.fn()}
        status={null}
      />,
    );

    expect(html).toContain('Base URL');
    expect(html).toContain('Embedding API key');
  });
});
