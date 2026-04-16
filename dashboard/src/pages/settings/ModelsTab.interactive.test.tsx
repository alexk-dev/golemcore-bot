/* @vitest-environment jsdom */

import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { MemoryRouter } from 'react-router-dom';
import type * as ReactRouterDom from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';

import type { LlmConfig, ModelRouterConfig } from '../../api/settingsTypes';

declare global {
  var IS_REACT_ACT_ENVIRONMENT: boolean | undefined;
}

globalThis.IS_REACT_ACT_ENVIRONMENT = true;

const navigateSpy = vi.hoisted(() => vi.fn());

vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof ReactRouterDom>('react-router-dom');
  return { ...actual, useNavigate: () => navigateSpy };
});

vi.mock('../../hooks/useSettings', () => ({
  useUpdateModelRouter: () => ({ mutateAsync: vi.fn(() => Promise.resolve()), isPending: false }),
}));

vi.mock('../../hooks/useModels', () => ({
  useAvailableModels: () => ({ data: {} }),
}));

import ModelsTab from './ModelsTab';

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
  },
};

const baseRouter: ModelRouterConfig = {
  routing: { model: null, reasoning: null, temperature: null, fallbackMode: 'sequential', fallbacks: [] },
  tiers: {
    balanced: { model: null, reasoning: null, temperature: null, fallbackMode: 'sequential', fallbacks: [] },
    smart: { model: null, reasoning: null, temperature: null, fallbackMode: 'sequential', fallbacks: [] },
    deep: { model: null, reasoning: null, temperature: null, fallbackMode: 'sequential', fallbacks: [] },
    coding: { model: null, reasoning: null, temperature: null, fallbackMode: 'sequential', fallbacks: [] },
    special1: { model: null, reasoning: null, temperature: null, fallbackMode: 'sequential', fallbacks: [] },
    special2: { model: null, reasoning: null, temperature: null, fallbackMode: 'sequential', fallbacks: [] },
    special3: { model: null, reasoning: null, temperature: null, fallbackMode: 'sequential', fallbacks: [] },
    special4: { model: null, reasoning: null, temperature: null, fallbackMode: 'sequential', fallbacks: [] },
    special5: { model: null, reasoning: null, temperature: null, fallbackMode: 'sequential', fallbacks: [] },
  },
  dynamicTierEnabled: true,
};

function queryButton(label: string): HTMLButtonElement | undefined {
  const buttons = Array.from(document.querySelectorAll<HTMLButtonElement>('button'));
  return buttons.find((btn) => (btn.textContent ?? '').trim() === label);
}

function findButtonStartingWith(label: string): HTMLButtonElement {
  const buttons = Array.from(document.querySelectorAll<HTMLButtonElement>('button'));
  const match = buttons.find((btn) => (btn.textContent ?? '').trim().startsWith(label));
  if (!match) {
    throw new Error(`Button "${label}" not found`);
  }
  return match;
}

function findDynamicTierSwitch(): HTMLInputElement {
  const input = document.querySelector<HTMLInputElement>('input[type="checkbox"]');
  if (!input) {
    throw new Error('Dynamic tier switch not found');
  }
  return input;
}

function cloneRouterReference(config: ModelRouterConfig): ModelRouterConfig {
  return {
    ...config,
    routing: { ...config.routing },
    tiers: { ...config.tiers },
  };
}

function renderModelsTab(root: Root, config: ModelRouterConfig): void {
  act(() => {
    root.render(
      <MemoryRouter>
        <ModelsTab config={config} llmConfig={llmConfig} />
      </MemoryRouter>,
    );
  });
}

function renderTab(): { root: Root; container: HTMLDivElement } {
  const container = document.createElement('div');
  document.body.appendChild(container);
  const root: Root = createRoot(container);
  renderModelsTab(root, baseRouter);
  return { root, container };
}

describe('ModelsTab confirm-on-dirty navigation to fallback editor', () => {
  afterEach(() => {
    navigateSpy.mockClear();
    document.body.innerHTML = '';
  });

  it('navigates to the tier fallback page immediately when not dirty', () => {
    const { root, container } = renderTab();

    act(() => {
      findButtonStartingWith('Configure fallbacks').click();
    });

    expect(navigateSpy).toHaveBeenCalledWith('/settings/models/routing');
    expect(queryButton('Leave')).toBeUndefined();

    act(() => { root.unmount(); });
    container.remove();
  });

  it('opens the confirm modal when dirty and cancels without navigating', () => {
    const { root, container } = renderTab();

    act(() => {
      findDynamicTierSwitch().click();
    });

    act(() => {
      findButtonStartingWith('Configure fallbacks').click();
    });

    expect(navigateSpy).not.toHaveBeenCalled();
    expect(queryButton('Leave')).toBeDefined();

    act(() => {
      findButtonStartingWith('Cancel').click();
    });

    expect(navigateSpy).not.toHaveBeenCalled();
    expect(queryButton('Leave')).toBeUndefined();

    act(() => { root.unmount(); });
    container.remove();
  });

  it('navigates to the requested tier after the user confirms Leave', () => {
    const { root, container } = renderTab();

    act(() => {
      findDynamicTierSwitch().click();
    });

    act(() => {
      findButtonStartingWith('Configure fallbacks').click();
    });

    act(() => {
      findButtonStartingWith('Leave').click();
    });

    expect(navigateSpy).toHaveBeenCalledWith('/settings/models/routing');

    act(() => { root.unmount(); });
    container.remove();
  });

  it('keeps dirty model edits when runtime config refetches before fallback navigation', () => {
    const { root, container } = renderTab();

    act(() => {
      findDynamicTierSwitch().click();
    });

    renderModelsTab(root, cloneRouterReference(baseRouter));

    act(() => {
      findButtonStartingWith('Configure fallbacks').click();
    });

    expect(navigateSpy).not.toHaveBeenCalled();
    expect(queryButton('Leave')).toBeDefined();

    act(() => { root.unmount(); });
    container.remove();
  });
});
