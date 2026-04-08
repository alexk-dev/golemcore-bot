import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import type { HiveStatusResponse } from '../../api/hive';
import type { LlmConfig, ModelRouterConfig } from '../../api/settings';
import LlmProvidersTab from './LlmProvidersTab';

vi.mock('../../hooks/useSettings', () => ({
  useAddLlmProvider: () => ({ isPending: false, mutateAsync: vi.fn() }),
  useUpdateLlmProvider: () => ({ isPending: false, mutateAsync: vi.fn() }),
  useRemoveLlmProvider: () => ({ isPending: false, mutateAsync: vi.fn() }),
}));

const config: LlmConfig = {
  providers: {
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

const modelRouter: ModelRouterConfig = {
  temperature: 0.7,
  routing: {
    model: { provider: 'openrouter', id: 'openai/gpt-5' },
    reasoning: null,
  },
  tiers: {
    balanced: { model: { provider: 'openrouter', id: 'openai/gpt-5' }, reasoning: null },
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
  targetPolicyVersion: 4,
  appliedPolicyVersion: 3,
  policySyncStatus: 'OUT_OF_SYNC',
  lastPolicyErrorDigest: 'provider openai missing',
};

describe('LlmProvidersTab', () => {
  it('renders provider input as combobox and keeps openrouter suggestion visible in the page', () => {
    const html = renderToStaticMarkup(
      <LlmProvidersTab config={config} modelRouter={modelRouter} />,
    );

    expect(html).toContain('LLM Providers');
    expect(html).toContain('autocomplete-combobox__control');
    expect(html).toContain('autocomplete-combobox__trigger');
    expect(html).toContain('role="combobox"');
    expect(html).toContain('openrouter');
    expect(html).toContain('Add Provider');
  });

  it('shows a managed-by-hive notice and disables local provider editing when a policy group is active', () => {
    const html = renderToStaticMarkup(
      <LlmProvidersTab config={config} modelRouter={modelRouter} hiveStatus={managedHiveStatus} />,
    );

    expect(html).toContain('LLM Providers managed by Hive');
    expect(html).toContain('policy-prod');
    expect(html).toContain('OUT_OF_SYNC');
    expect(html).toContain('Applied v3. Target v4.');
    expect(html).toContain('Last policy error: provider openai missing');
    expect(html).toContain('<fieldset disabled=""');
  });
});
