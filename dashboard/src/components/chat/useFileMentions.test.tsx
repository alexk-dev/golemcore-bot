/* @vitest-environment jsdom */

import { act, useRef, useState, type ReactElement } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { FileTreeNode } from '../../api/files';

interface GetFileTreeOptions {
  depth?: number;
  includeIgnored?: boolean;
}

const getFileTreeMock =
  vi.fn<(path: string, options?: GetFileTreeOptions) => Promise<FileTreeNode[]>>();
vi.mock('../../api/files', () => ({
  getFileTree: (path: string, options?: GetFileTreeOptions) => getFileTreeMock(path, options),
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

function dir(path: string, children: FileTreeNode[] = []): FileTreeNode {
  return {
    path,
    name: path.split('/').pop() ?? path,
    type: 'directory',
    binary: false,
    image: false,
    editable: false,
    hasChildren: children.length > 0,
    children,
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
    getFileTreeMock.mockImplementation((path: string) => {
      if (path === '' || path == null) {
        return Promise.resolve([dir('src', [file('src/a.ts'), file('src/b.ts')])]);
      }
      return Promise.resolve([]);
    });
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

  it('limits the initial fetch to a shallow depth', async () => {
    const harness = mountHarness();
    act(() => {
      harness.control.setText('hi @');
    });
    await flushPromises();

    expect(getFileTreeMock).toHaveBeenCalledTimes(1);
    const [firstPath, firstOptions] = getFileTreeMock.mock.calls[0] ?? [];
    expect(firstPath).toBe('');
    expect(firstOptions?.depth).toBeGreaterThanOrEqual(1);
    expect(firstOptions?.depth).toBeLessThanOrEqual(2);
    unmountHarness(harness);
  });

  it('lazily fetches a directory subtree when the query drills into it', async () => {
    getFileTreeMock.mockImplementation((path: string) => {
      if (path === '') {
        return Promise.resolve([dir('src', [dir('deep', [])])]);
      }
      if (path === 'src/deep') {
        return Promise.resolve([file('src/deep/nested.ts')]);
      }
      return Promise.resolve([]);
    });

    const harness = mountHarness();
    act(() => {
      harness.control.setText('hi @');
    });
    await flushPromises();

    const pathsFetched = (): string[] =>
      getFileTreeMock.mock.calls.map((call) => call[0]);
    expect(pathsFetched()).toEqual(['']);

    act(() => {
      harness.control.setText('hi @src/deep/');
    });
    await flushPromises();

    expect(pathsFetched()).toContain('src/deep');

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
