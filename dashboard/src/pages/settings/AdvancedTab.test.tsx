/* @vitest-environment jsdom */

import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import type { CompactionConfig, RateLimitConfig, ResilienceConfig, SecurityConfig } from '../../api/settingsTypes';
import { AdvancedTab } from './AdvancedTab';

declare global {
  var IS_REACT_ACT_ENVIRONMENT: boolean | undefined;
}

globalThis.IS_REACT_ACT_ENVIRONMENT = true;

const mutateAsync = vi.hoisted(() =>
  vi.fn<(config: {
    rateLimit?: RateLimitConfig;
    security?: SecurityConfig;
    compaction?: CompactionConfig;
    resilience?: ResilienceConfig;
  }) => Promise<void>>(() => Promise.resolve()),
);
const toastSuccess = vi.hoisted(() => vi.fn<(message: string) => void>());

vi.mock('react-hot-toast', () => ({
  default: {
    success: toastSuccess,
  },
}));

vi.mock('../../hooks/useSettings', () => ({
  useUpdateAdvanced: () => ({
    mutateAsync,
    isPending: false,
  }),
}));


const DEFAULT_RATE_LIMIT: RateLimitConfig = {
  enabled: true,
  userRequestsPerMinute: null,
  userRequestsPerHour: null,
  userRequestsPerDay: null,
};

const DEFAULT_SECURITY: SecurityConfig = {
  sanitizeInput: true,
  detectPromptInjection: true,
  detectCommandInjection: true,
  maxInputLength: null,
  allowlistEnabled: true,
  toolConfirmationEnabled: false,
  toolConfirmationTimeoutSeconds: null,
};

const DEFAULT_COMPACTION: CompactionConfig = {
  enabled: true,
  triggerMode: 'model_ratio',
  modelThresholdRatio: null,
  maxContextTokens: null,
  keepLastMessages: null,
};

function buildResilienceConfig(
  l2ProviderFallbackMaxAttempts: number,
  followThroughMaxChainDepth = 1,
): ResilienceConfig {
  return {
    enabled: true,
    hotRetryMaxAttempts: 5,
    hotRetryBaseDelayMs: 5000,
    hotRetryCapMs: 60000,
    l2ProviderFallbackMaxAttempts,
    circuitBreakerFailureThreshold: 5,
    circuitBreakerWindowSeconds: 60,
    circuitBreakerOpenDurationSeconds: 120,
    degradationCompactContext: true,
    degradationCompactMinMessages: 6,
    degradationDowngradeModel: true,
    degradationFallbackModelTier: 'balanced',
    degradationStripTools: true,
    coldRetryEnabled: true,
    coldRetryMaxAttempts: 4,
    followThrough: {
      enabled: true,
      modelTier: 'routing',
      timeoutSeconds: 5,
      maxChainDepth: followThroughMaxChainDepth,
    },
    autoProceed: {
      enabled: false,
      modelTier: 'routing',
      timeoutSeconds: 5,
      maxChainDepth: 2,
    },
  };
}

interface RenderResult {
  container: HTMLDivElement;
  unmount: () => void;
}

function renderAdvancedTab(resilience: ResilienceConfig): RenderResult {
  const container = document.createElement('div');
  document.body.appendChild(container);
  const root: Root = createRoot(container);

  act(() => {
    root.render(
      <AdvancedTab
        rateLimit={DEFAULT_RATE_LIMIT}
        security={DEFAULT_SECURITY}
        compaction={DEFAULT_COMPACTION}
        resilience={resilience}
        mode="resilience"
      />,
    );
  });

  return {
    container,
    unmount: () => {
      act(() => {
        root.unmount();
      });
      container.remove();
    },
  };
}

function getInputByLabel(container: HTMLElement, label: string): HTMLInputElement {
  const labels = Array.from(container.querySelectorAll('label'));
  const match = labels.find((node) => node.textContent?.includes(label));
  if (match == null) {
    throw new Error(`Label "${label}" not found`);
  }
  const group = match.parentElement;
  const input = group?.querySelector('input, select');
  if (!(input instanceof HTMLInputElement)) {
    throw new Error(`Input for label "${label}" not found`);
  }
  return input;
}

function getButtonByText(label: string): HTMLButtonElement {
  const buttons = Array.from(document.querySelectorAll('button'));
  const match = buttons.find((button) => button.textContent?.trim() === label);
  if (!(match instanceof HTMLButtonElement)) {
    throw new Error(`Button "${label}" not found`);
  }
  return match;
}

async function flushPromises(): Promise<void> {
  await act(async () => {
    await Promise.resolve();
  });
}

describe('AdvancedTab resilience settings', () => {
  afterEach(() => {
    document.body.innerHTML = '';
  });

  beforeEach(() => {
    mutateAsync.mockClear();
    toastSuccess.mockClear();
  });

  it('renders the L2 fallback max attempts field', () => {
    const view = renderAdvancedTab(buildResilienceConfig(5));

    expect(view.container.textContent).toContain('L2 Provider Fallback Max Attempts');

    view.unmount();
  });

  it('saves resilience config through the advanced settings mutation', async () => {
    const view = renderAdvancedTab(buildResilienceConfig(5));
    const input = getInputByLabel(view.container, 'L2 Provider Fallback Max Attempts');

    act(() => {
      const descriptor = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value');
      descriptor?.set?.call(input, '7');
      input.dispatchEvent(new Event('input', { bubbles: true }));
    });

    act(() => {
      getButtonByText('Save All').click();
    });
    await flushPromises();

    expect(mutateAsync).toHaveBeenCalledWith({
      rateLimit: DEFAULT_RATE_LIMIT,
      security: DEFAULT_SECURITY,
      compaction: DEFAULT_COMPACTION,
      resilience: buildResilienceConfig(7),
    });
    expect(toastSuccess).toHaveBeenCalledWith('Advanced settings saved');

    view.unmount();
  });

  it('renders follow-through nudge controls', () => {
    const view = renderAdvancedTab(buildResilienceConfig(5));

    expect(view.container.textContent).toContain('Follow-Through Nudge');
    expect(view.container.textContent).toContain('Classifier Model Tier');
    expect(view.container.textContent).toContain('Classifier Timeout');
    expect(view.container.textContent).toContain('Max Chain Depth');

    view.unmount();
  });

  it('saves follow-through max chain depth through the advanced settings mutation', async () => {
    const view = renderAdvancedTab(buildResilienceConfig(5, 1));
    const input = getInputByLabel(view.container, 'Max Chain Depth');

    act(() => {
      const descriptor = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value');
      descriptor?.set?.call(input, '3');
      input.dispatchEvent(new Event('input', { bubbles: true }));
    });

    act(() => {
      getButtonByText('Save All').click();
    });
    await flushPromises();

    expect(mutateAsync).toHaveBeenCalledWith({
      rateLimit: DEFAULT_RATE_LIMIT,
      security: DEFAULT_SECURITY,
      compaction: DEFAULT_COMPACTION,
      resilience: buildResilienceConfig(5, 3),
    });

    view.unmount();
  });
});
