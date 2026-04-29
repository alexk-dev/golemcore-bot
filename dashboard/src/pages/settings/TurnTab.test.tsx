/* @vitest-environment jsdom */

import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import type { ToolLoopConfig, TurnConfig } from '../../api/settingsTypes';
import TurnTab from './TurnTab';

declare global {
  var IS_REACT_ACT_ENVIRONMENT: boolean | undefined;
}

globalThis.IS_REACT_ACT_ENVIRONMENT = true;

const updateTurnMutateAsync = vi.hoisted(() =>
  vi.fn<(config: TurnConfig) => Promise<void>>(() => Promise.resolve()),
);
const updateToolLoopMutateAsync = vi.hoisted(() =>
  vi.fn<(config: ToolLoopConfig) => Promise<void>>(() => Promise.resolve()),
);
const toastSuccess = vi.hoisted(() => vi.fn<(message: string) => void>());

vi.mock('react-hot-toast', () => ({
  default: {
    success: toastSuccess,
  },
}));

vi.mock('../../hooks/useSettings', () => ({
  useUpdateTurn: () => ({
    mutateAsync: updateTurnMutateAsync,
    isPending: false,
  }),
  useUpdateToolLoop: () => ({
    mutateAsync: updateToolLoopMutateAsync,
    isPending: false,
  }),
}));

const TURN_CONFIG: TurnConfig = {
  maxLlmCalls: 20_000,
  maxToolExecutions: 500,
  deadline: 'PT1H',
  progressUpdatesEnabled: true,
  progressIntentEnabled: true,
  progressBatchSize: 8,
  progressMaxSilenceSeconds: 10,
  progressSummaryTimeoutMs: 8000,
};

const TOOL_LOOP_CONFIG: ToolLoopConfig = {
  maxLlmCalls: 20,
  maxToolExecutions: 80,
  repeatGuardEnabled: true,
  repeatGuardShadowMode: false,
  repeatGuardMaxSameObservePerTurn: 2,
  repeatGuardMaxSameUnknownPerTurn: 2,
  repeatGuardMaxBlockedRepeatsPerTurn: 4,
  repeatGuardMinPollIntervalSeconds: 60,
  repeatGuardAutoLedgerTtlMinutes: 120,
};

interface RenderResult {
  container: HTMLDivElement;
  unmount: () => void;
}

function renderTurnTab(
  turnConfig: TurnConfig = TURN_CONFIG,
  toolLoopConfig: ToolLoopConfig = TOOL_LOOP_CONFIG,
): RenderResult {
  const container = document.createElement('div');
  document.body.appendChild(container);
  const root: Root = createRoot(container);

  act(() => {
    root.render(<TurnTab config={turnConfig} toolLoopConfig={toolLoopConfig} />);
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
  const input = group?.querySelector('input');
  if (!(input instanceof HTMLInputElement)) {
    throw new Error(`Input for label "${label}" not found`);
  }
  return input;
}

function setInputValue(input: HTMLInputElement, value: string): void {
  act(() => {
    const descriptor = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value');
    descriptor?.set?.call(input, value);
    input.dispatchEvent(new Event('input', { bubbles: true }));
  });
}

function setCheckboxChecked(input: HTMLInputElement, checked: boolean): void {
  act(() => {
    const descriptor = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'checked');
    descriptor?.set?.call(input, checked);
    input.dispatchEvent(new Event('click', { bubbles: true }));
  });
}

function clickSave(): void {
  const buttons = Array.from(document.querySelectorAll('button'));
  const button = buttons.find((candidate) => candidate.textContent?.trim() === 'Save');
  if (!(button instanceof HTMLButtonElement)) {
    throw new Error('Save button not found');
  }
  act(() => {
    button.click();
  });
}

async function flushPromises(): Promise<void> {
  await act(async () => {
    await Promise.resolve();
  });
}

describe('TurnTab', () => {
  afterEach(() => {
    document.body.innerHTML = '';
  });

  beforeEach(() => {
    updateTurnMutateAsync.mockClear();
    updateToolLoopMutateAsync.mockClear();
    toastSuccess.mockClear();
  });

  it('edits internal AI call limits through tool-loop settings, not legacy turn settings', async () => {
    const view = renderTurnTab();

    setInputValue(getInputByLabel(view.container, 'Max Internal AI Calls'), '37');
    clickSave();
    await flushPromises();

    expect(updateToolLoopMutateAsync).toHaveBeenCalledWith({
      maxLlmCalls: 37,
      maxToolExecutions: 80,
      repeatGuardEnabled: true,
      repeatGuardShadowMode: false,
      repeatGuardMaxSameObservePerTurn: 2,
      repeatGuardMaxSameUnknownPerTurn: 2,
      repeatGuardMaxBlockedRepeatsPerTurn: 4,
      repeatGuardMinPollIntervalSeconds: 60,
      repeatGuardAutoLedgerTtlMinutes: 120,
    });
    expect(updateTurnMutateAsync).not.toHaveBeenCalled();
    expect(toastSuccess).toHaveBeenCalledWith('Turn budget settings saved');

    view.unmount();
  });

  it('edits repeat guard settings through tool-loop config', async () => {
    const view = renderTurnTab();
    const enabled = view.container.querySelector('#repeat-guard-enabled');
    const shadow = view.container.querySelector('#repeat-guard-shadow-mode');
    if (!(enabled instanceof HTMLInputElement) || !(shadow instanceof HTMLInputElement)) {
      throw new Error('Repeat guard switches not found');
    }

    setCheckboxChecked(enabled, false);
    setCheckboxChecked(shadow, true);
    setInputValue(getInputByLabel(view.container, 'Same Observe Calls'), '3');
    setInputValue(getInputByLabel(view.container, 'Same Unknown Calls'), '4');
    setInputValue(getInputByLabel(view.container, 'Blocked Repeats'), '5');
    setInputValue(getInputByLabel(view.container, 'Poll Backoff'), '30');
    setInputValue(getInputByLabel(view.container, 'Auto Ledger TTL'), '45');
    clickSave();
    await flushPromises();

    expect(updateToolLoopMutateAsync).toHaveBeenCalledWith({
      maxLlmCalls: 20,
      maxToolExecutions: 80,
      repeatGuardEnabled: false,
      repeatGuardShadowMode: true,
      repeatGuardMaxSameObservePerTurn: 3,
      repeatGuardMaxSameUnknownPerTurn: 4,
      repeatGuardMaxBlockedRepeatsPerTurn: 5,
      repeatGuardMinPollIntervalSeconds: 30,
      repeatGuardAutoLedgerTtlMinutes: 45,
    });
    expect(updateTurnMutateAsync).not.toHaveBeenCalled();

    view.unmount();
  });

  it('keeps deadline and live progress on turn settings', async () => {
    const view = renderTurnTab();

    setInputValue(getInputByLabel(view.container, 'Deadline'), 'PT30M');
    clickSave();
    await flushPromises();

    expect(updateTurnMutateAsync).toHaveBeenCalledWith({
      ...TURN_CONFIG,
      deadline: 'PT30M',
    });
    expect(updateToolLoopMutateAsync).not.toHaveBeenCalled();

    view.unmount();
  });
});
