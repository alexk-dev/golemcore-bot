import { renderToStaticMarkup } from 'react-dom/server';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import type { ReactElement } from 'react';

import type { HiveStatusResponse } from '../api/hive';
import type { LlmConfig, ModelRouterConfig, TierBinding } from '../api/settingsTypes';

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

function emptyBinding(): TierBinding {
  return { model: null, reasoning: null, temperature: null, fallbackMode: 'sequential', fallbacks: [] };
}

const baseModelRouterConfig: ModelRouterConfig = {
  routing: emptyBinding(),
  tiers: {
    balanced: emptyBinding(),
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

const runtimeConfigStub = vi.hoisted(() => ({
  current: null as { llm: LlmConfig; modelRouter: ModelRouterConfig } | null,
}));
const hiveStatusStub = vi.hoisted(() => ({
  current: undefined as HiveStatusResponse | undefined,
}));

vi.mock('../hooks/useSettings', () => ({
  useRuntimeConfig: () => ({ data: runtimeConfigStub.current, isLoading: runtimeConfigStub.current == null }),
  useUpdateModelRouter: () => ({ mutateAsync: vi.fn(() => Promise.resolve()), isPending: false }),
}));

vi.mock('../hooks/useModels', () => ({
  useAvailableModels: () => ({ data: availableModels }),
}));

vi.mock('../hooks/useHive', () => ({
  useHiveStatus: () => ({ data: hiveStatusStub.current }),
}));

import TierFallbacksPage from './TierFallbacksPage';

function renderTierPage(tier: string): string {
  return renderWithRouter(
    <MemoryRouter initialEntries={[`/settings/models/${tier}`]}>
      <Routes>
        <Route path="/settings/models/:tier" element={<TierFallbacksPage />} />
      </Routes>
    </MemoryRouter>,
  );
}

function renderWithRouter(ui: ReactElement): string {
  return renderToStaticMarkup(ui);
}

describe('TierFallbacksPage', () => {
  it('keeps unavailable configured fallback providers visible', () => {
    runtimeConfigStub.current = {
      llm: llmConfig,
      modelRouter: {
        ...baseModelRouterConfig,
        tiers: {
          ...baseModelRouterConfig.tiers,
          balanced: {
            ...baseModelRouterConfig.tiers.balanced,
            fallbacks: [
              { model: { provider: 'anthropic', id: 'claude-sonnet-4' }, reasoning: null, temperature: 0.4, weight: null },
            ],
          },
        },
      },
    };
    hiveStatusStub.current = undefined;

    const html = renderTierPage('balanced');

    expect(html).toContain('anthropic');
    expect(html).toContain('claude-sonnet-4 (unavailable)');
  });

  it('shows managed policy notice and disables editing when Hive manages model routing', () => {
    runtimeConfigStub.current = { llm: llmConfig, modelRouter: baseModelRouterConfig };
    hiveStatusStub.current = managedHiveStatus;

    const html = renderTierPage('coding');

    expect(html).toContain('Model Router managed by Hive');
    expect(html).toContain('policy-prod');
    expect(html).toContain('<fieldset disabled=""');
  });

  it('renders fallback for the routing tier', () => {
    runtimeConfigStub.current = { llm: llmConfig, modelRouter: baseModelRouterConfig };
    hiveStatusStub.current = undefined;

    const html = renderTierPage('routing');

    expect(html).toContain('Routing');
    expect(html).toContain('Fallback models (0/5)');
  });

  it('renders supported fallback strategies and weighted row weights', () => {
    runtimeConfigStub.current = {
      llm: llmConfig,
      modelRouter: {
        ...baseModelRouterConfig,
        tiers: {
          ...baseModelRouterConfig.tiers,
          balanced: {
            ...baseModelRouterConfig.tiers.balanced,
            fallbackMode: 'weighted',
            fallbacks: [
              { model: { provider: 'openai', id: 'gpt-5.1' }, reasoning: null, temperature: null, weight: 2.5 },
            ],
          },
        },
      },
    };
    hiveStatusStub.current = undefined;

    const html = renderTierPage('balanced');

    expect(html).toContain('Sequential');
    expect(html).toContain('Round robin');
    expect(html).toContain('Weighted');
    expect(html).toContain('Weight');
    expect(html).toContain('value="2.5"');
  });

  it('shows Tier not found for an unknown tier param', () => {
    runtimeConfigStub.current = { llm: llmConfig, modelRouter: baseModelRouterConfig };
    hiveStatusStub.current = undefined;

    const html = renderTierPage('bogus');

    expect(html).toContain('Tier not found');
  });
});
