import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';

import type { HiveStatusResponse } from '../../api/hive';
import type { LlmConfig, ModelRouterConfig, TierBinding } from '../../api/settingsTypes';
import ModelFallbacksTab from './ModelFallbacksTab';

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

function emptyBinding(): TierBinding {
  return { model: null, reasoning: null, temperature: null, fallbackMode: 'sequential', fallbacks: [] };
}

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
  },
};

const baseModelRouterConfig: ModelRouterConfig = {
  routing: emptyBinding(),
  tiers: {
    balanced: { model: { provider: 'openai', id: 'gpt-5.1' }, reasoning: 'none', temperature: 0.7, fallbackMode: 'sequential', fallbacks: [] },
    smart: emptyBinding(),
    deep: emptyBinding(),
    coding: emptyBinding(),
    special1: emptyBinding(),
    special2: emptyBinding(),
    special3: emptyBinding(),
    special4: emptyBinding(),
    special5: emptyBinding(),
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

describe('ModelFallbacksTab', () => {
  it('keeps unavailable configured fallback providers visible', () => {
    const config: ModelRouterConfig = {
      ...baseModelRouterConfig,
      tiers: {
        ...baseModelRouterConfig.tiers,
        balanced: {
          ...baseModelRouterConfig.tiers.balanced,
          fallbacks: [
            {
              model: { provider: 'anthropic', id: 'claude-sonnet-4' },
              reasoning: null,
              temperature: 0.4,
            },
          ],
        },
      },
    };

    const html = renderToStaticMarkup(
      <ModelFallbacksTab config={config} llmConfig={llmConfig} />,
    );

    expect(html).toContain('anthropic');
    expect(html).toContain('claude-sonnet-4 (unavailable)');
  });

  it('shows policy state and disables fallback editing when Hive manages model routing', () => {
    const html = renderToStaticMarkup(
      <ModelFallbacksTab config={baseModelRouterConfig} llmConfig={llmConfig} hiveStatus={managedHiveStatus} />,
    );

    expect(html).toContain('Model Router managed by Hive');
    expect(html).toContain('policy-prod');
    expect(html).toContain('<fieldset disabled=""');
  });
});
