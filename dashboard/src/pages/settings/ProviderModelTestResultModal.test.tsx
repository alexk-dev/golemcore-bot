import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import { ProviderModelTestResultModal } from './ProviderModelTestResultModal';

describe('ProviderModelTestResultModal', () => {
  it('renders provider test details and discovered model names', () => {
    const html = renderToStaticMarkup(
      <ProviderModelTestResultModal
        show
        result={{
          mode: 'draft',
          providerName: 'draftmesh',
          resolvedEndpoint: 'https://draft.example.com/v1/models',
          models: ['draftmesh/draft-gpt', 'draftmesh/draft-coder'],
          success: true,
          error: null,
        }}
        onHide={vi.fn()}
      />,
    );

    expect(html).toContain('Provider Test Result');
    expect(html).toContain('Mode');
    expect(html).toContain('draft');
    expect(html).toContain('draftmesh/draft-gpt');
    expect(html).toContain('draftmesh/draft-coder');
  });

  it('renders request errors when the provider test fails', () => {
    const html = renderToStaticMarkup(
      <ProviderModelTestResultModal
        show
        result={{
          mode: 'saved',
          providerName: 'openrouter',
          resolvedEndpoint: null,
          models: [],
          success: false,
          error: 'bad gateway',
        }}
        onHide={vi.fn()}
      />,
    );

    expect(html).toContain('saved');
    expect(html).toContain('bad gateway');
  });
});
