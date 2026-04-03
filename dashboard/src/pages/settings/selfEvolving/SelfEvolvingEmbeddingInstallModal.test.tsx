import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';

import { SelfEvolvingEmbeddingInstallModal } from './SelfEvolvingEmbeddingInstallModal';

describe('SelfEvolvingEmbeddingInstallModal', () => {
  it('renders a blocking install progress modal for the selected model', () => {
    const html = renderToStaticMarkup(
      <SelfEvolvingEmbeddingInstallModal
        show
        model="qwen3-embedding:0.6b"
      />,
    );

    expect(html).toContain('Installing local embedding model');
    expect(html).toContain('qwen3-embedding:0.6b');
    expect(html).toContain('Embedding installation progress');
    expect(html).toContain('This dialog closes automatically when the model is ready.');
  });
});
