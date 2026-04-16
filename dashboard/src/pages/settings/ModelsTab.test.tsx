import { renderToStaticMarkup } from 'react-dom/server';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { HiveStatusResponse } from '../../api/hive';
import type { LlmConfig, ModelRouterConfig } from '../../api/settingsTypes';
import ModelsTab from './ModelsTab';

const availableModels = {
  openai: [
    {
      id: 'openai/gpt-5.1',
      displayName: 'GPT-5.1',
      hasReasoning: true,
      reasoningLevels: ['none', 'medium'],
      supportsVision: true,
      supportsTemperature: true,
    },
  ],
  openrouter: [
    {
      id: 'openrouter/qwen/model-name:version',
      displayName: 'Qwen Model',
      hasReasoning: false,
      reasoningLevels: [],
      supportsVision: true,
      supportsTemperature: true,
    },
  ],
};

vi.mock('../../hooks/useSettings', () => ({
  useUpdateModelRouter: () => ({
    mutateAsync: vi.fn(() => Promise.resolve()),
    isPending: false,
  }),
}));

vi.mock('../../hooks/useModels', () => ({
  useAvailableModels: () => ({
    data: availableModels,
  }),
}));

const llmConfig: LlmConfig = {
  providers: {
    openai: {
      apiKey: null,
      apiKeyPresent: true,
      baseUrl: null,
      requestTimeoutSeconds: null,
      apiType: 'openai',
      legacyApi: null,
    },
    anthropic: {
      apiKey: null,
      apiKeyPresent: false,
      baseUrl: null,
      requestTimeoutSeconds: null,
      apiType: 'anthropic',
      legacyApi: null,
    },
    openrouter: {
      apiKey: null,
      apiKeyPresent: true,
      baseUrl: 'https://openrouter.ai/api/v1',
      requestTimeoutSeconds: 30,
      apiType: 'openai',
      legacyApi: null,
    },
  },
};

const modelRouterConfig: ModelRouterConfig = {
  routing: {
    model: { provider: 'openai', id: 'gpt-5.1' },
    reasoning: 'none',
    temperature: 0.7,
    fallbackMode: 'sequential',
    fallbacks: [],
  },
  tiers: {
    balanced: { model: { provider: 'openai', id: 'gpt-5.1' }, reasoning: 'none', temperature: 0.7, fallbackMode: 'sequential', fallbacks: [] },
    smart: { model: { provider: 'openai', id: 'gpt-5.1' }, reasoning: 'medium', temperature: 0.7, fallbackMode: 'sequential', fallbacks: [] },
    deep: { model: { provider: 'openai', id: 'gpt-5.1' }, reasoning: 'medium', temperature: 0.7, fallbackMode: 'sequential', fallbacks: [] },
    coding: { model: { provider: 'openai', id: 'gpt-5.1' }, reasoning: 'medium', temperature: 0.7, fallbackMode: 'sequential', fallbacks: [] },
    special1: { model: null, reasoning: null, temperature: null, fallbackMode: 'sequential', fallbacks: [] },
    special2: { model: null, reasoning: null, temperature: null, fallbackMode: 'sequential', fallbacks: [] },
    special3: { model: null, reasoning: null, temperature: null, fallbackMode: 'sequential', fallbacks: [] },
    special4: { model: null, reasoning: null, temperature: null, fallbackMode: 'sequential', fallbacks: [] },
    special5: { model: null, reasoning: null, temperature: null, fallbackMode: 'sequential', fallbacks: [] },
  },
  dynamicTierEnabled: true,
};

const managedHiveStatus: HiveStatusResponse = {
  state: 'CONNECTED',
  enabled: true,
  managedByProperties: false,
  managedJoinCodeAvailable: false,
  autoConnect: true,
  serverUrl: 'https://hive.example.com',
  displayName: 'Build Runner',
  hostLabel: 'builder-a',
  dashboardBaseUrl: 'https://bot.example.com/dashboard',
  ssoEnabled: true,
  sessionPresent: true,
  golemId: 'golem-1',
  controlChannelUrl: 'wss://hive.example.com/ws',
  heartbeatIntervalSeconds: 30,
  lastConnectedAt: null,
  lastHeartbeatAt: null,
  lastTokenRotatedAt: null,
  controlChannelState: 'CONNECTED',
  controlChannelConnectedAt: null,
  controlChannelLastMessageAt: null,
  controlChannelLastError: null,
  lastReceivedCommandId: null,
  lastReceivedCommandAt: null,
  receivedCommandCount: 0,
  bufferedCommandCount: 0,
  pendingCommandCount: 0,
  pendingEventBatchCount: 0,
  pendingEventCount: 0,
  outboxLastError: null,
  lastError: null,
  policyGroupId: 'policy-prod',
  targetPolicyVersion: 8,
  appliedPolicyVersion: 8,
  policySyncStatus: 'IN_SYNC',
  lastPolicyErrorDigest: null,
};

describe('ModelsTab', () => {
  beforeEach(() => {
    modelRouterConfig.tiers.special1 = { model: null, reasoning: null, temperature: null, fallbackMode: 'sequential', fallbacks: [] };
  });

  it('renders optional special tiers with an explicit empty model option', () => {
    const html = renderToStaticMarkup(
      <ModelsTab config={modelRouterConfig} llmConfig={llmConfig} />,
    );

    expect(html.match(/Not configured/g)?.length ?? 0).toBe(5);
  });

  it('keeps unavailable configured special tiers visible instead of pretending they are empty', () => {
    modelRouterConfig.tiers.special1 = { model: { provider: 'anthropic', id: 'claude-sonnet-4' }, reasoning: null, temperature: null, fallbackMode: 'sequential', fallbacks: [] };

    const html = renderToStaticMarkup(
      <ModelsTab config={modelRouterConfig} llmConfig={llmConfig} />,
    );

    expect(html).toContain('anthropic (unavailable)');
    expect(html).toContain('claude-sonnet-4 (unavailable)');
  });

  it('hides the implicit openrouter prefix in routing model selects', () => {
    modelRouterConfig.routing = {
      model: { provider: 'openrouter', id: 'qwen/model-name:version' },
      reasoning: null,
      temperature: null,
      fallbackMode: 'sequential',
      fallbacks: [],
    };

    const html = renderToStaticMarkup(
      <ModelsTab config={modelRouterConfig} llmConfig={llmConfig} />,
    );

    expect(html).toContain('qwen/model-name:version');
    expect(html).not.toContain('openrouter/qwen/model-name:version');
  });

  it('shows policy state and disables router editing when Hive manages the section', () => {
    const html = renderToStaticMarkup(
      <ModelsTab config={modelRouterConfig} llmConfig={llmConfig} hiveStatus={managedHiveStatus} />,
    );

    expect(html).toContain('Model Router managed by Hive');
    expect(html).toContain('policy-prod');
    expect(html).toContain('IN_SYNC');
    expect(html).toContain('Applied v8. Target v8.');
    expect(html).toContain('<fieldset disabled=""');
  });
});
