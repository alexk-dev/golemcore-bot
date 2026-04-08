/* @vitest-environment jsdom */

import { act } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createRoot, type Root } from 'react-dom/client';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const recordCounter = vi.hoisted(() => vi.fn());
const recordKeyedCounter = vi.hoisted(() => vi.fn());

vi.mock('./settings/GeneralTab', () => ({ default: () => <div>general</div> }));
vi.mock('./settings/AdvancedTab', () => ({ AdvancedTab: () => <div>advanced</div> }));
vi.mock('./settings/ToolsTab', () => ({ default: () => <div>tools</div> }));
vi.mock('./settings/ModelsTab', () => ({ default: () => <div>models</div> }));
vi.mock('./settings/ModelCatalogTab', () => ({ ModelCatalogTab: () => <div>model-catalog</div> }));
vi.mock('./settings/LlmProvidersTab', () => ({ default: () => <div>llm</div> }));
vi.mock('./settings/VoiceRoutingTab', () => ({ default: () => <div>voice</div> }));
vi.mock('./settings/MemoryTab', () => ({ default: () => <div>memory</div> }));
vi.mock('./settings/SkillsTab', () => ({ default: () => <div>skills</div> }));
vi.mock('./settings/TurnTab', () => ({ default: () => <div>turn</div> }));
vi.mock('./settings/UsageTab', () => ({ default: () => <div>usage</div> }));
vi.mock('./settings/TelemetryTab', () => ({ default: () => <div>telemetry</div> }));
vi.mock('./settings/McpTab', () => ({ default: () => <div>mcp</div> }));
vi.mock('./settings/HiveTab', () => ({ default: () => <div>hive</div> }));
vi.mock('./settings/AutoModeTab', () => ({ default: () => <div>auto</div> }));
vi.mock('./settings/PlanModeTab', () => ({ default: () => <div>plan</div> }));
vi.mock('./settings/SelfEvolvingTab', () => ({ default: () => <div>self-evolving</div> }));
vi.mock('./settings/TracingTab', () => ({ default: () => <div>tracing</div> }));
vi.mock('./settings/UpdatesTab', () => ({ UpdatesTab: () => <div>updates</div> }));
vi.mock('./settings/PluginSettingsPanel', () => ({ default: () => <div>plugin-settings</div> }));
vi.mock('./settings/PluginsMarketplaceTab', () => ({ default: () => <div>marketplace</div> }));

vi.mock('../hooks/useSettings', () => ({
  useSettings: () => ({ data: {}, isLoading: false }),
  useRuntimeConfig: () => ({ data: {}, isLoading: false }),
  useUpdateRuntimeConfig: () => ({ mutateAsync: vi.fn(), isPending: false }),
}));

vi.mock('../hooks/usePlugins', () => ({
  usePluginMarketplace: () => ({ data: null }),
  usePluginSettingsCatalog: () => ({ data: [], isLoading: false }),
}));

vi.mock('../hooks/useAuth', () => ({
  useMe: () => ({ data: null }),
}));

vi.mock('../hooks/useSelfEvolving', () => ({
  useInstallSelfEvolvingTacticEmbeddingModel: () => ({ mutateAsync: vi.fn(), isPending: false }),
  useSelfEvolvingTacticSearchStatus: () => ({ data: null }),
}));

vi.mock('../lib/telemetry/TelemetryProvider', () => ({
  useTelemetry: () => ({
    recordCounter,
    recordKeyedCounter,
    recordCounterByRoute: vi.fn(),
    recordUiError: vi.fn(),
  }),
}));

import SettingsPage from './SettingsPage';

declare global {
  var IS_REACT_ACT_ENVIRONMENT: boolean | undefined;
}

globalThis.IS_REACT_ACT_ENVIRONMENT = true;

describe('SettingsPage telemetry', () => {
  afterEach(() => {
    document.body.innerHTML = '';
  });

  beforeEach(() => {
    recordCounter.mockClear();
    recordKeyedCounter.mockClear();
  });

  it('records settings search usage when the user types in the catalog search box', () => {
    const queryClient = new QueryClient();
    const container = document.createElement('div');
    document.body.appendChild(container);
    const root: Root = createRoot(container);

    act(() => {
      root.render(
        <QueryClientProvider client={queryClient}>
          <MemoryRouter initialEntries={['/settings']}>
            <Routes>
              <Route path="/settings" element={<SettingsPage />} />
            </Routes>
          </MemoryRouter>
        </QueryClientProvider>,
      );
    });

    const input = document.querySelector('input[type="search"]');
    if (!(input instanceof HTMLInputElement)) {
      throw new Error('Search input not found');
    }

    const setValue = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set?.bind(input);
    if (setValue == null) {
      throw new Error('Input value setter not found');
    }

    act(() => {
      setValue('telemetry');
      input.dispatchEvent(new Event('change', { bubbles: true }));
    });

    expect(recordCounter).toHaveBeenCalledWith('settings_open_count');
    expect(recordKeyedCounter).toHaveBeenCalledWith('settings_section_views_by_key', 'catalog');
    expect(recordCounter).toHaveBeenCalledWith('settings_search_count');

    act(() => {
      root.unmount();
    });
  });
});
