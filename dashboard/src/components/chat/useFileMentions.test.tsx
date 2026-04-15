/* @vitest-environment jsdom */

import { act, useRef, useState, type ReactElement } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { FileTreeNode } from '../../api/files';

const getFileTreeMock = vi.fn<() => Promise<FileTreeNode[]>>();
vi.mock('../../api/files', () => ({
  getFileTree: (...args: unknown[]) => getFileTreeMock(...(args as [])),
}));

import { useFileMentions } from './useFileMentions';

declare global {
  var IS_REACT_ACT_ENVIRONMENT: boolean | undefined;
}

globalThis.IS_REACT_ACT_ENVIRONMENT = true;

function file(path: string): FileTreeNode {
  return {
    path,
    name: path.split('/').pop() ?? path,
    type: 'file',
    binary: false,
    image: false,
    editable: true,
    hasChildren: false,
    children: [],
  };
}

interface HarnessControl {
  setText: (value: string) => void;
  setCaret: (value: number) => void;
  getText: () => string;
}

interface HarnessShape {
  container: HTMLDivElement;
  root: Root;
  control: HarnessControl;
}

function HarnessHost({
  registerControl,
}: {
  registerControl: (control: HarnessControl) => void;
}): ReactElement {
  const [text, setText] = useState('');
  const textareaRef = useRef<HTMLTextAreaElement | null>(null);
  const mentions = useFileMentions({ text, setText, textareaRef });

  registerControl({
    setText: (value: string) => {
      setText(value);
      if (textareaRef.current) {
        textareaRef.current.value = value;
        textareaRef.current.selectionStart = value.length;
        textareaRef.current.selectionEnd = value.length;
      }
    },
    setCaret: (value: number) => {
      if (textareaRef.current) {
        textareaRef.current.selectionStart = value;
        textareaRef.current.selectionEnd = value;
      }
    },
    getText: () => text,
  });

  return (
    <div>
      <textarea ref={textareaRef} readOnly value={text} data-testid="harness-textarea" />
      {mentions.menu}
    </div>
  );
}

function mountHarness(): HarnessShape {
  const container = document.createElement('div');
  document.body.appendChild(container);
  const root = createRoot(container);
  const latest: { current: HarnessControl | null } = { current: null };
  act(() => {
    root.render(
      <HarnessHost
        registerControl={(registered) => {
          latest.current = registered;
        }}
      />,
    );
  });
  if (!latest.current) {
    throw new Error('harness control was not registered');
  }
  const proxy: HarnessControl = {
    setText: (value: string) => latest.current?.setText(value),
    setCaret: (value: number) => latest.current?.setCaret(value),
    getText: () => latest.current?.getText() ?? '',
  };
  return { container, root, control: proxy };
}

function unmountHarness({ container, root }: HarnessShape): void {
  act(() => {
    root.unmount();
  });
  container.remove();
}

async function flushPromises(): Promise<void> {
  await act(async () => {
    await Promise.resolve();
    await Promise.resolve();
  });
}

describe('useFileMentions', () => {
  beforeEach(() => {
    getFileTreeMock.mockReset();
    getFileTreeMock.mockResolvedValue([file('src/a.ts'), file('src/b.ts')]);
    document.body.innerHTML = '';
  });

  afterEach(() => {
    document.body.innerHTML = '';
  });

  it('does not render the menu when text has no mention trigger', async () => {
    const harness = mountHarness();
    act(() => {
      harness.control.setText('hello world');
    });
    await flushPromises();
    expect(harness.container.querySelector('[role="listbox"]')).toBeNull();
    unmountHarness(harness);
  });

  it('fetches and renders file options when user types @', async () => {
    const harness = mountHarness();
    act(() => {
      harness.control.setText('hi @');
    });
    await flushPromises();

    expect(getFileTreeMock).toHaveBeenCalled();
    const listbox = harness.container.querySelector('[role="listbox"]');
    expect(listbox).not.toBeNull();
    const options = harness.container.querySelectorAll('[data-testid^="file-mention-option-"]');
    expect(options.length).toBeGreaterThan(0);
    unmountHarness(harness);
  });

  it('inserts the selected file path into the textarea on click', async () => {
    const harness = mountHarness();
    act(() => {
      harness.control.setText('ref @a');
    });
    await flushPromises();

    const option = harness.container.querySelector<HTMLButtonElement>(
      '[data-testid="file-mention-option-0"]',
    );
    expect(option).not.toBeNull();
    act(() => {
      option?.click();
    });

    expect(harness.control.getText()).toBe('ref @src/a.ts ');
    unmountHarness(harness);
  });
});
