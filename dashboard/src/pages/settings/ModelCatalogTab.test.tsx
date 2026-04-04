import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import type { LlmConfig, ModelRegistryConfig } from '../../api/settings';
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
});
