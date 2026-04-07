/* @vitest-environment jsdom */

import { act, useEffect } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const recordCounter = vi.hoisted(() => vi.fn());
const saveModel = vi.hoisted(() => vi.fn(() => Promise.resolve()));
const reloadModels = vi.hoisted(() => vi.fn(() => Promise.resolve()));
const refetchModelsConfig = vi.hoisted(() => vi.fn(() => Promise.resolve()));

vi.mock('react-hot-toast', () => ({
  default: {
    success: vi.fn(),
    error: vi.fn(),
  },
}));

vi.mock('../../../lib/telemetry/TelemetryProvider', () => ({
  useTelemetry: () => ({
    recordCounter,
    recordKeyedCounter: vi.fn(),
    recordCounterByRoute: vi.fn(),
    recordUiError: vi.fn(),
  }),
}));

vi.mock('../../../hooks/useModels', () => ({
  useModelsConfig: () => ({
    data: {
      models: {},
      defaults: {
        provider: 'openai',
        displayName: null,
        supportsVision: false,
        supportsTemperature: true,
        maxInputTokens: 32000,
        reasoning: null,
      },
    },
    isLoading: false,
    error: null,
    refetch: refetchModelsConfig,
  }),
  useSaveModel: () => ({
    mutateAsync: saveModel,
    isPending: false,
  }),
  useDeleteModel: () => ({
    mutateAsync: vi.fn(() => Promise.resolve()),
    isPending: false,
  }),
  useReloadModels: () => ({
    mutateAsync: reloadModels,
    isPending: false,
  }),
  useResolveModelRegistry: () => ({
    mutateAsync: vi.fn(() => Promise.resolve({ defaultSettings: null })),
    isPending: false,
  }),
}));

vi.mock('./ModelCatalogSidebar', () => ({
  ModelCatalogSidebar: ({
    onReload,
  }: {
    onReload: () => void;
  }) => (
    <button type="button" onClick={onReload}>Reload</button>
  ),
}));

vi.mock('./ModelCatalogForm', () => ({
  ModelCatalogForm: ({
    onDraftChange,
    onSave,
  }: {
    onDraftChange: (draft: {
      id: string;
      provider: string;
      displayName: string;
      supportsVision: boolean;
      supportsTemperature: boolean;
      maxInputTokens: string;
      reasoningEnabled: boolean;
      reasoningDefault: string;
      reasoningLevels: Array<{ level: string; maxInputTokens: string }>;
    }) => void;
    onSave: () => void;
  }) => {
    useEffect(() => {
      onDraftChange({
        id: 'gpt-4.1',
        provider: 'openai',
        displayName: 'GPT-4.1',
        supportsVision: true,
        supportsTemperature: true,
        maxInputTokens: '128000',
        reasoningEnabled: false,
        reasoningDefault: '',
        reasoningLevels: [],
      });
    }, [onDraftChange]);

    return (
      <button type="button" onClick={onSave}>Save model</button>
    );
  },
}));

import { ModelCatalogEditor } from './ModelCatalogEditor';

declare global {
  var IS_REACT_ACT_ENVIRONMENT: boolean | undefined;
}

globalThis.IS_REACT_ACT_ENVIRONMENT = true;

describe('ModelCatalogEditor telemetry', () => {
  afterEach(() => {
    document.body.innerHTML = '';
  });

  beforeEach(() => {
    recordCounter.mockClear();
    saveModel.mockClear();
    reloadModels.mockClear();
    refetchModelsConfig.mockClear();
  });

  it('records model catalog edit counts when a model is saved', async () => {
    const container = document.createElement('div');
    document.body.appendChild(container);
    const root: Root = createRoot(container);

    await act(async () => {
      root.render(
        <ModelCatalogEditor
          providerProfiles={[
            { name: 'openai', apiType: 'openai', isReady: true },
          ]}
        />,
      );
    });

    const buttons = Array.from(document.querySelectorAll('button'));
    const saveButton = buttons.find((button) => button.textContent?.trim() === 'Save model') as HTMLButtonElement;
    const reloadButton = buttons.find((button) => button.textContent?.trim() === 'Reload') as HTMLButtonElement;

    await act(async () => {
      saveButton.click();
      reloadButton.click();
    });

    expect(recordCounter).toHaveBeenCalledWith('model_catalog_edit_count');
    expect(recordCounter).toHaveBeenCalledWith('model_reload_count');

    act(() => {
      root.unmount();
    });
  });
});
