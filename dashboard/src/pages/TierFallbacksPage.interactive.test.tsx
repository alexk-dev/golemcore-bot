/* @vitest-environment jsdom */

import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import type * as ReactRouterDom from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import type { LlmConfig, ModelRouterConfig } from '../api/settingsTypes';

declare global {
  var IS_REACT_ACT_ENVIRONMENT: boolean | undefined;
}

globalThis.IS_REACT_ACT_ENVIRONMENT = true;

const navigateSpy = vi.hoisted(() => vi.fn());
const updateRouterCalls = vi.hoisted(() => ({
  current: [] as ModelRouterConfig[],
}));
const settingsStub = vi.hoisted(() => ({
  runtime: null as { data: unknown; isLoading: boolean } | null,
  updateRouter: null as { mutateAsync: (config: ModelRouterConfig) => Promise<void>; isPending: boolean } | null,
}));

vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof ReactRouterDom>('react-router-dom');
  return { ...actual, useNavigate: () => navigateSpy };
});

vi.mock('../hooks/useSettings', () => ({
  useRuntimeConfig: () => settingsStub.runtime,
  useUpdateModelRouter: () => settingsStub.updateRouter,
}));

vi.mock('../hooks/useModels', () => ({
  useAvailableModels: () => ({ data: {} }),
}));

vi.mock('../hooks/useHive', () => ({
  useHiveStatus: () => ({ data: undefined }),
}));

import TierFallbacksPage from './TierFallbacksPage';

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

function findButton(label: string): HTMLButtonElement {
  const buttons = Array.from(document.querySelectorAll<HTMLButtonElement>('button'));
  const match = buttons.find((btn) => (btn.textContent ?? '').trim().startsWith(label));
  if (!match) {
    throw new Error(`Button "${label}" not found among: ${buttons.map((b) => (b.textContent ?? '').trim()).join(' | ')}`);
  }
  return match;
}

function queryButton(label: string): HTMLButtonElement | undefined {
  const buttons = Array.from(document.querySelectorAll<HTMLButtonElement>('button'));
  return buttons.find((btn) => (btn.textContent ?? '').trim() === label);
}

function findFallbackModeSelect(): HTMLSelectElement {
  const selects = Array.from(document.querySelectorAll<HTMLSelectElement>('select'));
  const match = selects.find((select) => Array.from(select.options).some((option) => option.value === 'weighted'));
  if (!match) {
    throw new Error('Fallback mode select not found');
  }
  return match;
}

function findWeightInput(): HTMLInputElement {
  const match = document.querySelector<HTMLInputElement>('input[type="number"]');
  if (match == null) {
    throw new Error('Fallback weight input not found');
  }
  return match;
}

function changeWeightInputToTwoPointFive(input: HTMLInputElement): void {
  input.stepUp(15);
  input.dispatchEvent(new Event('input', { bubbles: true }));
}

function cloneRouterReference(config: ModelRouterConfig): ModelRouterConfig {
  return {
    ...config,
    routing: { ...config.routing },
    tiers: { ...config.tiers },
  };
}

function renderTierFallbacksPage(root: Root): void {
  act(() => {
    root.render(
      <MemoryRouter initialEntries={['/settings/models/balanced']}>
        <Routes>
          <Route path="/settings/models/:tier" element={<TierFallbacksPage />} />
        </Routes>
      </MemoryRouter>,
    );
  });
}

function renderPage(): { root: Root; container: HTMLDivElement } {
  const container = document.createElement('div');
  document.body.appendChild(container);
  const root: Root = createRoot(container);
  renderTierFallbacksPage(root);
  return { root, container };
}

describe('TierFallbacksPage confirm-on-dirty navigation', () => {
  beforeEach(() => {
    settingsStub.runtime = { data: { llm: llmConfig, modelRouter: baseRouter }, isLoading: false };
    updateRouterCalls.current = [];
    settingsStub.updateRouter = {
      mutateAsync: (config: ModelRouterConfig) => {
        updateRouterCalls.current.push(config);
        return Promise.resolve();
      },
      isPending: false,
    };
  });

  afterEach(() => {
    navigateSpy.mockClear();
    document.body.innerHTML = '';
  });

  it('navigates back immediately when there are no unsaved changes', () => {
    const { root, container } = renderPage();

    act(() => {
      findButton('Back to Model Router').click();
    });

    expect(navigateSpy).toHaveBeenCalledWith('/settings/models');
    expect(queryButton('Leave')).toBeUndefined();

    act(() => { root.unmount(); });
    container.remove();
  });

  it('opens the confirm modal when back is clicked while dirty and respects Cancel', () => {
    const { root, container } = renderPage();

    act(() => {
      findButton('Add fallback').click();
    });

    act(() => {
      findButton('Back to Model Router').click();
    });

    expect(navigateSpy).not.toHaveBeenCalled();
    expect(queryButton('Leave')).toBeDefined();

    act(() => {
      findButton('Cancel').click();
    });

    expect(navigateSpy).not.toHaveBeenCalled();
    expect(queryButton('Leave')).toBeUndefined();

    act(() => { root.unmount(); });
    container.remove();
  });

  it('navigates after the user confirms Leave', () => {
    const { root, container } = renderPage();

    act(() => {
      findButton('Add fallback').click();
    });

    act(() => {
      findButton('Back to Model Router').click();
    });

    act(() => {
      findButton('Leave').click();
    });

    expect(navigateSpy).toHaveBeenCalledWith('/settings/models');

    act(() => { root.unmount(); });
    container.remove();
  });

  it('saves weighted fallback mode with row weights', () => {
    const { root, container } = renderPage();

    act(() => {
      const select = findFallbackModeSelect();
      select.value = 'weighted';
      select.dispatchEvent(new Event('change', { bubbles: true }));
    });

    act(() => {
      findButton('Add fallback').click();
    });

    act(() => {
      const input = findWeightInput();
      changeWeightInputToTwoPointFive(input);
    });

    act(() => {
      findButton('Save Fallback Settings').click();
    });

    expect(updateRouterCalls.current).toHaveLength(1);
    const savedBalancedTier = updateRouterCalls.current[0].tiers.balanced;
    expect(savedBalancedTier.fallbackMode).toBe('weighted');
    expect(savedBalancedTier.fallbacks[0].weight).toBe(2.5);

    act(() => { root.unmount(); });
    container.remove();
  });

  it('keeps dirty fallback edits when runtime config refetches before back navigation', () => {
    const { root, container } = renderPage();

    act(() => {
      findButton('Add fallback').click();
    });

    settingsStub.runtime = {
      data: { llm: llmConfig, modelRouter: cloneRouterReference(baseRouter) },
      isLoading: false,
    };
    renderTierFallbacksPage(root);

    act(() => {
      findButton('Back to Model Router').click();
    });

    expect(navigateSpy).not.toHaveBeenCalled();
    expect(queryButton('Leave')).toBeDefined();

    act(() => { root.unmount(); });
    container.remove();
  });
});
