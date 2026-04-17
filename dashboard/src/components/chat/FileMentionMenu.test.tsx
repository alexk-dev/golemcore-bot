/* @vitest-environment jsdom */

import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { FileMentionMenu } from './FileMentionMenu';
import type { MentionFileEntry } from './fileMentions';

declare global {
  var IS_REACT_ACT_ENVIRONMENT: boolean | undefined;
}

globalThis.IS_REACT_ACT_ENVIRONMENT = true;

interface Harness {
  container: HTMLDivElement;
  root: Root;
}

const files: MentionFileEntry[] = [
  { path: 'src/a.ts', name: 'a.ts' },
  { path: 'src/b.ts', name: 'b.ts' },
];

function mount(props: Parameters<typeof FileMentionMenu>[0]): Harness {
  const container = document.createElement('div');
  document.body.appendChild(container);
  const root = createRoot(container);
  act(() => {
    root.render(<FileMentionMenu {...props} />);
  });
  return { container, root };
}

function unmount({ container, root }: Harness): void {
  act(() => {
    root.unmount();
  });
  container.remove();
}

describe('FileMentionMenu', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
  });

  afterEach(() => {
    document.body.innerHTML = '';
  });

  it('renders one option per suggestion', () => {
    const harness = mount({ suggestions: files, onSelect: vi.fn() });
    const options = harness.container.querySelectorAll('[data-testid^="file-mention-option-"]');
    expect(options).toHaveLength(2);
    expect(options[0]?.textContent).toContain('a.ts');
    unmount(harness);
  });

  it('invokes onSelect with the path when an option is clicked', () => {
    const onSelect = vi.fn();
    const harness = mount({ suggestions: files, onSelect });
    const option = harness.container.querySelector<HTMLButtonElement>(
      '[data-testid="file-mention-option-0"]',
    );
    act(() => {
      option?.click();
    });
    expect(onSelect).toHaveBeenCalledWith('src/a.ts');
    unmount(harness);
  });

  it('renders a disabled empty-state row when there are no suggestions', () => {
    const harness = mount({ suggestions: [], onSelect: vi.fn() });
    const empty = harness.container.querySelector('[data-testid="file-mention-empty"]');
    expect(empty).not.toBeNull();
    unmount(harness);
  });
});
