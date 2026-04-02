import { beforeEach, describe, expect, it, vi } from 'vitest';

const clientGetMock = vi.fn();
const clientPutMock = vi.fn();

vi.mock('./client', () => ({
  default: {
    get: clientGetMock,
    put: clientPutMock,
  },
}));

function buildRuntimeConfigFixture(selfEvolving: Record<string, unknown>) {
  return {
    telegram: {
      enabled: false,
      token: null,
      authMode: 'invite_only',
      allowedUsers: [],
      inviteCodes: [],
    },
    modelRouter: {
      temperature: null,
      routing: { model: null, reasoning: null },
      tiers: {
        balanced: { model: null, reasoning: null },
        smart: { model: null, reasoning: null },
        deep: { model: null, reasoning: null },
        coding: { model: null, reasoning: null },
        special1: { model: null, reasoning: null },
        special2: { model: null, reasoning: null },
        special3: { model: null, reasoning: null },
        special4: { model: null, reasoning: null },
        special5: { model: null, reasoning: null },
      },
      dynamicTierEnabled: true,
    },
    modelRegistry: {
      repositoryUrl: null,
      branch: 'main',
    },
    llm: {
      providers: {},
    },
    tools: {
      filesystemEnabled: true,
      shellEnabled: true,
      skillManagementEnabled: true,
      skillTransitionEnabled: true,
      tierEnabled: true,
      goalManagementEnabled: true,
      shellEnvironmentVariables: [],
    },
    voice: {
      enabled: false,
      apiKey: null,
      voiceId: null,
      ttsModelId: null,
      sttModelId: null,
      speed: null,
      telegramRespondWithVoice: false,
      telegramTranscribeIncoming: false,
      sttProvider: null,
      ttsProvider: null,
      whisperSttUrl: null,
      whisperSttApiKey: null,
    },
    memory: {},
    skills: {},
    turn: {},
    usage: { enabled: true },
    mcp: { enabled: false, defaultStartupTimeout: null, defaultIdleTimeout: null, catalog: [] },
    plan: { enabled: false, maxPlans: null, maxStepsPerPlan: null, stopOnFailure: true },
    hive: { enabled: false, serverUrl: null, displayName: null, hostLabel: null, autoConnect: false, managedByProperties: false },
    selfEvolving,
    autoMode: {},
    tracing: {},
    rateLimit: {},
    security: {},
    compaction: {},
  };
}

describe('settings selfEvolving normalization', () => {
  beforeEach(() => {
    clientGetMock.mockReset();
    clientPutMock.mockReset();
  });

  it('normalizes missing and legacy selfevolving judge tiers to supported model tiers', async () => {
    clientGetMock.mockResolvedValue({
      data: buildRuntimeConfigFixture({
        enabled: false,
        judge: {
          primaryTier: 'standard',
          tiebreakerTier: 'premium',
          evolutionTier: 'premium',
        },
        tactics: {
          search: {
            embeddings: {
              enabled: true,
              provider: 'ollama',
              baseUrl: 'http://localhost:11434',
              model: 'qwen3-embedding:0.6b',
            },
          },
        },
      }),
    });

    const api = await import('./settings');
    const result = await api.getRuntimeConfig();

    expect(result.selfEvolving.judge.primaryTier).toBe('smart');
    expect(result.selfEvolving.judge.tiebreakerTier).toBe('deep');
    expect(result.selfEvolving.judge.evolutionTier).toBe('deep');
    expect(result.selfEvolving.tactics.search.mode).toBe('bm25');
    expect(result.selfEvolving.tactics.search.embeddings.provider).toBe('ollama');
    expect(result.selfEvolving.tactics.search.embeddings.model).toBe('qwen3-embedding:0.6b');
    expect(result.selfEvolving.tactics.search.embeddings.local.requireHealthyRuntime).toBe(true);
  });

  it('round-trips selfevolving tactic embeddings through runtime config updates', async () => {
    clientPutMock.mockResolvedValue({
      data: buildRuntimeConfigFixture({
        enabled: true,
        tracePayloadOverride: true,
        capture: { llm: 'full', tool: 'full', context: 'full', skill: 'full', tier: 'full', infra: 'meta_only' },
        judge: {
          enabled: true,
          primaryTier: 'smart',
          tiebreakerTier: 'deep',
          evolutionTier: 'deep',
          requireEvidenceAnchors: true,
          uncertaintyThreshold: 0.22,
        },
        evolution: { enabled: true, modes: ['fix'], artifactTypes: ['skill'] },
        tactics: {
          enabled: true,
          search: {
            mode: 'hybrid',
            embeddings: {
              enabled: true,
              provider: 'openai_compatible',
              baseUrl: 'https://api.example.com/v1',
              apiKey: 'emb-key',
              model: 'text-embedding-3-large',
              dimensions: 3072,
              batchSize: 32,
              timeoutMs: 10000,
              autoFallbackToBm25: true,
              local: {
                autoInstall: false,
                pullOnStart: false,
                requireHealthyRuntime: true,
                failOpen: true,
              },
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
        benchmark: { enabled: true, harvestProductionRuns: true, autoCreateRegressionCases: true },
        hive: { publishInspectionProjection: true, readonlyInspection: true },
      }),
    });

    const api = await import('./settings');
    const result = await api.updateRuntimeConfig(buildRuntimeConfigFixture({
      enabled: true,
      tracePayloadOverride: true,
      capture: { llm: 'full', tool: 'full', context: 'full', skill: 'full', tier: 'full', infra: 'meta_only' },
      judge: {
        enabled: true,
        primaryTier: 'smart',
        tiebreakerTier: 'deep',
        evolutionTier: 'deep',
        requireEvidenceAnchors: true,
        uncertaintyThreshold: 0.22,
      },
      evolution: { enabled: true, modes: ['fix'], artifactTypes: ['skill'] },
      tactics: {
        enabled: true,
        search: {
          mode: 'hybrid',
          embeddings: {
            enabled: true,
            provider: 'openai_compatible',
            baseUrl: 'https://api.example.com/v1',
            apiKey: 'emb-key',
            model: 'text-embedding-3-large',
            dimensions: 3072,
            batchSize: 32,
            timeoutMs: 10000,
            autoFallbackToBm25: true,
            local: {
              autoInstall: false,
              pullOnStart: false,
              requireHealthyRuntime: true,
              failOpen: true,
            },
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
      benchmark: { enabled: true, harvestProductionRuns: true, autoCreateRegressionCases: true },
      hive: { publishInspectionProjection: true, readonlyInspection: true },
    }) as never);

    expect(clientPutMock).toHaveBeenCalled();
    const payload = clientPutMock.mock.calls[0][1];
    expect(payload.selfEvolving.tactics.search.mode).toBe('hybrid');
    expect(payload.selfEvolving.tactics.search.embeddings.provider).toBe('openai_compatible');
    expect(payload.selfEvolving.tactics.search.embeddings.model).toBe('text-embedding-3-large');
    expect(payload.selfEvolving.tactics.search.embeddings.local.autoInstall).toBe(false);
    expect(payload.selfEvolving.tactics.search.embeddings.local.requireHealthyRuntime).toBe(true);
    expect(result.selfEvolving.tactics.search.embeddings.baseUrl).toBe('https://api.example.com/v1');
  });
});
