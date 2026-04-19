/* @vitest-environment jsdom */

import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { InlineDiffView } from './InlineDiffView';
import type { ProposedEdit } from '../../store/proposedEditStore';

class TestResizeObserver {
  observe(): void {}
  unobserve(): void {}
  disconnect(): void {}
}

if (typeof globalThis.ResizeObserver === 'undefined') {
  (globalThis as unknown as { ResizeObserver: typeof TestResizeObserver }).ResizeObserver =
    TestResizeObserver;
}

declare global {
  var IS_REACT_ACT_ENVIRONMENT: boolean | undefined;
}

globalThis.IS_REACT_ACT_ENVIRONMENT = true;

interface Harness {
  container: HTMLDivElement;
  root: Root;
}

const sampleProposal: ProposedEdit = {
  id: 'prop-1',
  path: 'src/example.ts',
  sourceContent: 'const greeting = "hi";\n',
  before: 'const greeting = "hi";\n',
  after: 'const greeting = "hello";\n',
  instruction: 'refactor this greeting',
  selection: {
    from: 0,
    to: 23,
    selectedText: 'const greeting = "hi";\n',
  },
};

function mount(props: {
  proposal: ProposedEdit;
  onAccept: (id: string) => void;
  onReject: (id: string) => void;
}): Harness {
  const container = document.createElement('div');
  document.body.appendChild(container);
  const root = createRoot(container);
  act(() => {
    root.render(<InlineDiffView {...props} />);
  });
  return { container, root };
}

function unmount({ container, root }: Harness): void {
  act(() => {
    root.unmount();
  });
  container.remove();
}

describe('InlineDiffView', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
  });

  afterEach(() => {
    document.body.innerHTML = '';
  });

  it('renders the proposal path in the header', () => {
    const harness = mount({
      proposal: sampleProposal,
      onAccept: vi.fn(),
      onReject: vi.fn(),
    });
    expect(harness.container.textContent).toContain('src/example.ts');
    unmount(harness);
  });

  it('renders the instruction in the header', () => {
    const harness = mount({
      proposal: sampleProposal,
      onAccept: vi.fn(),
      onReject: vi.fn(),
    });
    expect(harness.container.textContent).toContain('refactor this greeting');
    unmount(harness);
  });

  it('invokes onAccept with the proposal id when accept is clicked', () => {
    const onAccept = vi.fn();
    const harness = mount({
      proposal: sampleProposal,
      onAccept,
      onReject: vi.fn(),
    });
    const button = harness.container.querySelector<HTMLButtonElement>(
      '[data-testid="inline-diff-accept"]',
    );
    act(() => {
      button?.click();
    });
    expect(onAccept).toHaveBeenCalledWith('prop-1');
    unmount(harness);
  });

  it('invokes onReject with the proposal id when reject is clicked', () => {
    const onReject = vi.fn();
    const harness = mount({
      proposal: sampleProposal,
      onAccept: vi.fn(),
      onReject,
    });
    const button = harness.container.querySelector<HTMLButtonElement>(
      '[data-testid="inline-diff-reject"]',
    );
    act(() => {
      button?.click();
    });
    expect(onReject).toHaveBeenCalledWith('prop-1');
    unmount(harness);
  });

  it('renders the merge editor container', () => {
    const harness = mount({
      proposal: sampleProposal,
      onAccept: vi.fn(),
      onReject: vi.fn(),
    });
    const host = harness.container.querySelector('[data-testid="inline-diff-merge"]');
    expect(host).not.toBeNull();
    unmount(harness);
  });
});
