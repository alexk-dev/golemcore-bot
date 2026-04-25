/* @vitest-environment jsdom */

import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { SchedulerScheduledTask } from '../../api/scheduler';
import { ScheduledTaskEditorModal } from './ScheduledTaskEditorModal';

declare global {
  var IS_REACT_ACT_ENVIRONMENT: boolean | undefined;
}

globalThis.IS_REACT_ACT_ENVIRONMENT = true;

const createdTask: SchedulerScheduledTask = {
  id: 'scheduled-task-1',
  title: 'Nightly cleanup',
  description: null,
  prompt: null,
  executionMode: 'AGENT_PROMPT',
  shellCommand: null,
  shellWorkingDirectory: null,
  reflectionModelTier: null,
  reflectionTierPriority: false,
  legacySourceType: null,
  legacySourceId: null,
};

function findButton(label: string): HTMLButtonElement {
  const button = Array.from(document.body.querySelectorAll<HTMLButtonElement>('button'))
    .find((candidate) => (candidate.textContent ?? '').trim() === label);
  if (button == null) {
    throw new Error(`Button not found: ${label}`);
  }
  return button;
}

function findTitleInput(): HTMLInputElement {
  const input = document.body.querySelector<HTMLInputElement>('input[placeholder="Nightly repository sweep"]');
  if (input == null) {
    throw new Error('Scheduled task title input not found');
  }
  return input;
}

function setInputValue(input: HTMLInputElement, value: string): void {
  const descriptor = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value');
  if (descriptor?.set == null) {
    throw new Error('Native input value setter not found');
  }
  descriptor.set.call(input, value);
  input.dispatchEvent(new Event('input', { bubbles: true }));
}

async function flushPromises(): Promise<void> {
  await Promise.resolve();
  await Promise.resolve();
}

describe('ScheduledTaskEditorModal', () => {
  afterEach(() => {
    document.body.innerHTML = '';
  });

  it('clears create form state after closing and reopening', async () => {
    const container = document.createElement('div');
    document.body.appendChild(container);
    const root: Root = createRoot(container);
    let show = true;

    const renderModal = (): void => {
      root.render(
        <ScheduledTaskEditorModal
          show={show}
          featureEnabled
          busy={false}
          scheduleBusy={false}
          task={null}
          reportChannelOptions={[]}
          onHide={() => {
            show = false;
          }}
          onCreate={vi.fn(() => Promise.resolve(createdTask))}
          onCreateSchedule={vi.fn()}
          onUpdate={vi.fn(() => Promise.resolve())}
        />,
      );
    };

    await act(async () => {
      renderModal();
      await flushPromises();
    });

    await act(async () => {
      const input = findTitleInput();
      setInputValue(input, 'Stale task title');
      await flushPromises();
    });
    expect(findTitleInput().value).toBe('Stale task title');
    expect(findButton('Create task').disabled).toBe(false);

    await act(async () => {
      findButton('Cancel').click();
      await flushPromises();
    });
    await act(async () => {
      renderModal();
      await flushPromises();
    });

    show = true;
    await act(async () => {
      renderModal();
      await flushPromises();
    });

    expect(findTitleInput().value).toBe('');

    act(() => {
      root.unmount();
    });
    container.remove();
  });
});
