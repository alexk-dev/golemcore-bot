import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { ModelCatalogSidebar } from './ModelCatalogSidebar';

describe('ModelCatalogSidebar', () => {
  it('hides the implicit openrouter prefix in the model list', () => {
    const html = renderToStaticMarkup(
      <ModelCatalogSidebar
        groups={[
          {
            provider: 'openrouter',
            items: [
              {
                id: 'openrouter/qwen/model-name:version',
                settings: {
                  provider: 'openrouter',
                  displayName: null,
                  supportsVision: true,
                  supportsTemperature: true,
                  maxInputTokens: 128000,
                  reasoning: null,
                },
              },
            ],
          },
        ]}
        providerProfiles={[
          {
            name: 'openrouter',
            apiType: 'openai',
            isReady: true,
          },
        ]}
        selectedProviderName="openrouter"
        selectedModelId="openrouter/qwen/model-name:version"
        isReloading={false}
        onCreateNew={() => {}}
        onOpenSuggestions={() => {}}
        onReload={() => {}}
        onSelectProvider={() => {}}
        onSelectModel={() => {}}
      />,
    );

    expect(html).toContain('qwen/model-name:version');
    expect(html).not.toContain('openrouter/qwen/model-name:version');
  });
});
