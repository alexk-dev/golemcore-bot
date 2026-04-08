import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import { ProviderModelImportResultModal } from './ProviderModelImportResultModal';

describe('ProviderModelImportResultModal', () => {
  it('renders named added, skipped, and error model sections', () => {
    const html = renderToStaticMarkup(
      <ProviderModelImportResultModal
        show
        result={{
          providerSaved: true,
          providerName: 'xmesh',
          resolvedEndpoint: 'https://models.example.com/v1/models',
          addedModels: ['xmesh/gpt-5.2'],
          skippedModels: ['xmesh/existing'],
          errors: ['xmesh/broken: timeout'],
        }}
        onHide={vi.fn()}
      />,
    );

    expect(html).toContain('Provider Saved');
    expect(html).toContain('Added Models');
    expect(html).toContain('xmesh/gpt-5.2');
    expect(html).toContain('Skipped Existing Models');
    expect(html).toContain('xmesh/existing');
    expect(html).toContain('Errors');
    expect(html).toContain('xmesh/broken: timeout');
  });
});
