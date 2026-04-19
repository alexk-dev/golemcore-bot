/* @vitest-environment jsdom */

import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

class TestResizeObserver {
  observe(): void {}
  unobserve(): void {}
  disconnect(): void {}
}

if (typeof globalThis.ResizeObserver === 'undefined') {
  (globalThis as unknown as { ResizeObserver: typeof TestResizeObserver }).ResizeObserver =
    TestResizeObserver;
}

vi.mock('./InlineDiffView', () => ({
  InlineDiffView: ({
    proposal,
    onAccept,
    onReject,
  }: {
    proposal: { id: string; path: string; instruction: string };
    onAccept: (id: string) => void;
    onReject: (id: string) => void;
  }) => (
    <div data-testid={`mock-inline-diff-${proposal.id}`}>
      <span>{proposal.path}</span>
      <span>{proposal.instruction}</span>
      <button type="button" data-testid={`mock-accept-${proposal.id}`} onClick={() => onAccept(proposal.id)}>
        accept
      </button>
      <button type="button" data-testid={`mock-reject-${proposal.id}`} onClick={() => onReject(proposal.id)}>
        reject
      </button>
    </div>
  ),
}));

import { ProposedEditsPanel } from './ProposedEditsPanel';
import { useProposedEditStore } from '../../store/proposedEditStore';
import { useIdeStore, createNewTab } from '../../store/ideStore';

declare global {
  var IS_REACT_ACT_ENVIRONMENT: boolean | undefined;
}

globalThis.IS_REACT_ACT_ENVIRONMENT = true;

interface Harness {
  container: HTMLDivElement;
  root: Root;
}

function mount(): Harness {
  const container = document.createElement('div');
  document.body.appendChild(container);
  const root = createRoot(container);
  act(() => {
    root.render(<ProposedEditsPanel />);
  });
  return { container, root };
}

function unmount({ container, root }: Harness): void {
  act(() => {
    root.unmount();
  });
  container.remove();
}

describe('ProposedEditsPanel', () => {
  beforeEach(() => {
    useProposedEditStore.setState({ proposals: [] });
    useIdeStore.setState({
      openedTabs: [],
      activePath: null,
      recentPaths: [],
      pinnedPaths: [],
    });
    document.body.innerHTML = '';
  });

  afterEach(() => {
    document.body.innerHTML = '';
  });

  it('renders nothing when there are no proposals', () => {
    const harness = mount();
    expect(harness.container.querySelector('[data-testid^="mock-inline-diff-"]')).toBeNull();
    unmount(harness);
  });

  it('renders only the active file proposal', () => {
    useIdeStore.getState().upsertTab(createNewTab('src/a.ts', 'old a'));
    useIdeStore.getState().upsertTab(createNewTab('src/b.ts', 'old b'));
    useIdeStore.getState().setActivePath('src/b.ts');
    useProposedEditStore.getState().submitProposal({
      path: 'src/a.ts',
      sourceContent: 'old a',
      before: 'old a',
      after: 'new a',
      instruction: 'edit a',
      selection: { from: 0, to: 5, selectedText: 'old a' },
    });
    useProposedEditStore.getState().submitProposal({
      path: 'src/b.ts',
      sourceContent: 'old b',
      before: 'old b',
      after: 'new b',
      instruction: 'edit b',
      selection: { from: 0, to: 5, selectedText: 'old b' },
    });

    const harness = mount();
    const rendered = harness.container.querySelectorAll('[data-testid^="mock-inline-diff-"]');
    expect(rendered).toHaveLength(1);
    expect(harness.container.textContent).toContain('src/b.ts');
    expect(harness.container.textContent).not.toContain('src/a.ts');
    unmount(harness);
  });

  it('applying a proposal updates an opened tab and dismisses the proposal', () => {
    useIdeStore.getState().upsertTab(createNewTab('src/a.ts', 'old content'));
    useIdeStore.getState().setActivePath('src/a.ts');
    useProposedEditStore.getState().submitProposal({
      path: 'src/a.ts',
      sourceContent: 'old content',
      before: 'old',
      after: 'new',
      instruction: 'replace old with new',
      selection: { from: 0, to: 3, selectedText: 'old' },
    });

    const harness = mount();
    const id = useProposedEditStore.getState().proposals[0]?.id ?? '';
    const acceptButton = harness.container.querySelector<HTMLButtonElement>(
      `[data-testid="mock-accept-${id}"]`,
    );
    act(() => {
      acceptButton?.click();
    });

    const tab = useIdeStore.getState().openedTabs.find((entry) => entry.path === 'src/a.ts');
    expect(tab?.content).toBe('new content');
    expect(tab?.isDirty).toBe(true);
    expect(useProposedEditStore.getState().proposals).toEqual([]);
    unmount(harness);
  });

  it('rejecting a proposal just dismisses it', () => {
    useIdeStore.getState().upsertTab(createNewTab('src/c.ts', 'a'));
    useIdeStore.getState().setActivePath('src/c.ts');
    useProposedEditStore.getState().submitProposal({
      path: 'src/c.ts',
      sourceContent: 'a',
      before: 'a',
      after: 'b',
      instruction: 'replace a with b',
      selection: { from: 0, to: 1, selectedText: 'a' },
    });
    const harness = mount();
    const id = useProposedEditStore.getState().proposals[0]?.id ?? '';
    const rejectButton = harness.container.querySelector<HTMLButtonElement>(
      `[data-testid="mock-reject-${id}"]`,
    );
    act(() => {
      rejectButton?.click();
    });
    expect(useProposedEditStore.getState().proposals).toEqual([]);
    unmount(harness);
  });
});
