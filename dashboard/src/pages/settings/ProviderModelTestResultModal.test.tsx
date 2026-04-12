/* @vitest-environment jsdom */

import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ProviderModelTestResultModal } from './ProviderModelTestResultModal';

declare global {
  var IS_REACT_ACT_ENVIRONMENT: boolean | undefined;
}

globalThis.IS_REACT_ACT_ENVIRONMENT = true;

describe('ProviderModelTestResultModal', () => {
  afterEach(() => {
    document.body.innerHTML = '';
    document.body.style.overflow = '';
  });

  it('renders provider test details and discovered model names in the shared modal overlay', () => {
    const container = document.createElement('div');
    document.body.appendChild(container);
    const root: Root = createRoot(container);

    act(() => {
      root.render(
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
    });

    const pageText = document.body.textContent ?? '';
    expect(pageText).toContain('Provider Test Result');
    expect(pageText).toContain('Mode');
    expect(pageText).toContain('draft');
    expect(pageText).toContain('draftmesh/draft-gpt');
    expect(pageText).toContain('draftmesh/draft-coder');
    expect(document.body.style.overflow).toBe('hidden');

    act(() => {
      root.unmount();
    });
    container.remove();
  });

  it('closes through backdrop click when the provider test modal is open', () => {
    const onHide = vi.fn();
    const container = document.createElement('div');
    document.body.appendChild(container);
    const root: Root = createRoot(container);

    act(() => {
      root.render(
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
          onHide={onHide}
        />,
      );
    });

    const backdrop = document.body.querySelector('.fixed.inset-0.z-\\[1100\\]');
    if (!(backdrop instanceof HTMLDivElement)) {
      throw new Error('Modal backdrop not found');
    }

    act(() => {
      backdrop.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }));
    });

    expect(document.body.textContent ?? '').toContain('bad gateway');
    expect(onHide).toHaveBeenCalledTimes(1);

    act(() => {
      root.unmount();
    });
    container.remove();
  });
});
