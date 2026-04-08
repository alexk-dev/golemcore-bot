import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import { LlmProviderEditorCard } from './LlmProviderEditorCard';

describe('LlmProviderEditorCard', () => {
  it('renders both draft and saved provider test actions for existing providers', () => {
    const html = renderToStaticMarkup(
      <LlmProviderEditorCard
        name="openrouter"
        form={{
          apiKey: null,
          apiKeyPresent: true,
          baseUrl: 'https://openrouter.ai/api/v1',
          requestTimeoutSeconds: 30,
          apiType: 'openai',
          legacyApi: null,
        }}
        isNew={false}
        showKey={false}
        isSaving={false}
        isTesting={false}
        onFormChange={vi.fn()}
        onToggleShowKey={vi.fn()}
        onSave={vi.fn()}
        onCancel={vi.fn()}
        onTestDraft={vi.fn()}
        onTestSaved={vi.fn()}
      />,
    );

    expect(html).toContain('Test Draft');
    expect(html).toContain('Test Saved');
    expect(html).toContain('Save');
  });

  it('hides the saved-provider test action for new providers', () => {
    const html = renderToStaticMarkup(
      <LlmProviderEditorCard
        name="draftmesh"
        form={{
          apiKey: null,
          apiKeyPresent: false,
          baseUrl: 'https://draft.example.com/v1',
          requestTimeoutSeconds: 30,
          apiType: 'openai',
          legacyApi: null,
        }}
        isNew
        showKey={false}
        isSaving={false}
        isTesting={false}
        onFormChange={vi.fn()}
        onToggleShowKey={vi.fn()}
        onSave={vi.fn()}
        onCancel={vi.fn()}
        onTestDraft={vi.fn()}
        onTestSaved={vi.fn()}
      />,
    );

    expect(html).toContain('Test Draft');
    expect(html).not.toContain('Test Saved');
  });
});
