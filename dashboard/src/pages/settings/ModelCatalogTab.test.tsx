import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import type { HiveStatusResponse } from '../../api/hive';
import type { LlmConfig, ModelRegistryConfig } from '../../api/settingsTypes';
import { ModelCatalogTab } from './ModelCatalogTab';

vi.mock('react-router-dom', () => ({
  useNavigate: () => vi.fn(),
}));

vi.mock('./models/ModelCatalogEditor', () => ({
  ModelCatalogEditor: () => <div>Mocked catalog editor</div>,
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

const configuredModelRegistry: ModelRegistryConfig = {
  repositoryUrl: 'https://github.com/alexk-dev/golemcore-models',
  branch: 'main',
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
  targetPolicyVersion: 2,
  appliedPolicyVersion: 1,
  policySyncStatus: 'SYNC_PENDING',
  lastPolicyErrorDigest: null,
};

describe('ModelCatalogTab', () => {
  it('renders the model registry source card with the current repository config', () => {
    const html = renderToStaticMarkup(
      <ModelCatalogTab
        llmConfig={llmConfig}
        modelRegistryConfig={configuredModelRegistry}
        isSavingModelRegistry={false}
        onSaveModelRegistry={vi.fn(async () => {})}
      />,
    );

    expect(html).toContain('Model Registry Source');
    expect(html).toContain('golemcore-models');
    expect(html).toContain('Configured');
    expect(html).toContain('24h cache TTL');
  });

  it('shows an unconfigured state when no repository URL is set', () => {
    const html = renderToStaticMarkup(
      <ModelCatalogTab
        llmConfig={llmConfig}
        modelRegistryConfig={{ repositoryUrl: null, branch: 'main' }}
        isSavingModelRegistry={false}
        onSaveModelRegistry={vi.fn(async () => {})}
      />,
    );

    expect(html).toContain('Not configured');
    expect(html).toContain('Select a discovered model to resolve registry defaults on demand.');
  });

  it('shows openrouter as an api-ready provider profile', () => {
    const html = renderToStaticMarkup(
      <ModelCatalogTab
        llmConfig={llmConfig}
        modelRegistryConfig={configuredModelRegistry}
        isSavingModelRegistry={false}
        onSaveModelRegistry={vi.fn(async () => {})}
      />,
    );

    expect(html).toContain('openrouter');
    expect(html).toContain('Provider-first discovery');
    expect(html).toContain('API-ready');
  });

  it('shows the Hive policy notice and disables catalog editing when a policy group is active', () => {
    const html = renderToStaticMarkup(
      <ModelCatalogTab
        llmConfig={llmConfig}
        modelRegistryConfig={configuredModelRegistry}
        isSavingModelRegistry={false}
        hiveStatus={managedHiveStatus}
        onSaveModelRegistry={vi.fn(async () => {})}
      />,
    );

    expect(html).toContain('Model Catalog managed by Hive');
    expect(html).toContain('policy-prod');
    expect(html).toContain('SYNC_PENDING');
    expect(html).toContain('Applied v1. Target v2.');
    expect(html).toContain('<fieldset disabled=""');
  });
});
