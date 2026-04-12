/* @vitest-environment jsdom */

import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ProviderModelImportResultModal } from './ProviderModelImportResultModal';

declare global {
  var IS_REACT_ACT_ENVIRONMENT: boolean | undefined;
}

globalThis.IS_REACT_ACT_ENVIRONMENT = true;

function renderModal(): { unmount: () => void } {
  const container = document.createElement('div');
  document.body.appendChild(container);
  const root: Root = createRoot(container);

  act(() => {
    root.render(
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
  });

  return {
    unmount: () => {
      act(() => {
        root.unmount();
      });
      container.remove();
    },
  };
}

describe('ProviderModelImportResultModal', () => {
  afterEach(() => {
    document.body.innerHTML = '';
    document.body.style.overflow = '';
  });

  it('renders named added, skipped, and error model sections in the shared modal overlay', () => {
    const view = renderModal();

    const pageText = document.body.textContent ?? '';
    expect(pageText).toContain('Provider Saved');
    expect(pageText).toContain('Added Models');
    expect(pageText).toContain('xmesh/gpt-5.2');
    expect(pageText).toContain('Skipped Existing Models');
    expect(pageText).toContain('xmesh/existing');
    expect(pageText).toContain('Errors');
    expect(pageText).toContain('xmesh/broken: timeout');
    expect(document.body.style.overflow).toBe('hidden');

    view.unmount();
  });

  it('closes through Escape via the shared modal shell', () => {
    const onHide = vi.fn();
    const container = document.createElement('div');
    document.body.appendChild(container);
    const root: Root = createRoot(container);

    act(() => {
      root.render(
        <ProviderModelImportResultModal
          show
          result={{
            providerSaved: true,
            providerName: 'xmesh',
            resolvedEndpoint: 'https://models.example.com/v1/models',
            addedModels: [],
            skippedModels: [],
            errors: [],
          }}
          onHide={onHide}
        />,
      );
    });

    act(() => {
      window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    });

    expect(onHide).toHaveBeenCalledTimes(1);

    act(() => {
      root.unmount();
    });
    container.remove();
  });
});
