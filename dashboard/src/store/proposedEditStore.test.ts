import { beforeEach, describe, expect, it } from 'vitest';
import { useProposedEditStore } from './proposedEditStore';

function resetStore(): void {
  useProposedEditStore.setState({ proposals: [] });
}

describe('proposedEditStore', () => {
  beforeEach(() => {
    resetStore();
  });

  it('starts empty', () => {
    expect(useProposedEditStore.getState().proposals).toEqual([]);
  });

  it('submitProposal appends a new proposal with a generated id', () => {
    useProposedEditStore.getState().submitProposal({
      path: 'src/foo.ts',
      sourceContent: 'const x = 1;\n',
      before: 'const x = 1;\n',
      after: 'const x = 2;\n',
      instruction: 'refactor this',
      selection: {
        from: 0,
        to: 13,
        selectedText: 'const x = 1;\n',
      },
    });
    const { proposals } = useProposedEditStore.getState();
    expect(proposals).toHaveLength(1);
    expect(proposals[0]?.path).toBe('src/foo.ts');
    expect(proposals[0]?.sourceContent).toBe('const x = 1;\n');
    expect(proposals[0]?.before).toBe('const x = 1;\n');
    expect(proposals[0]?.after).toBe('const x = 2;\n');
    expect(proposals[0]?.instruction).toBe('refactor this');
    expect(proposals[0]?.selection).toEqual({
      from: 0,
      to: 13,
      selectedText: 'const x = 1;\n',
    });
    expect(typeof proposals[0]?.id).toBe('string');
    expect(proposals[0]?.id.length).toBeGreaterThan(0);
  });

  it('dismissProposal removes by id', () => {
    const store = useProposedEditStore.getState();
    store.submitProposal({
      path: 'a.ts',
      sourceContent: 'hello',
      before: '',
      after: 'hello',
      instruction: 'insert',
      selection: { from: 0, to: 0, selectedText: '' },
    });
    store.submitProposal({
      path: 'b.ts',
      sourceContent: 'world',
      before: '',
      after: 'world',
      instruction: 'insert',
      selection: { from: 0, to: 0, selectedText: '' },
    });

    const targetId = useProposedEditStore.getState().proposals[0]?.id ?? '';
    useProposedEditStore.getState().dismissProposal(targetId);

    const remaining = useProposedEditStore.getState().proposals;
    expect(remaining).toHaveLength(1);
    expect(remaining[0]?.path).toBe('b.ts');
  });

  it('submitProposal replaces existing proposal for the same path', () => {
    const store = useProposedEditStore.getState();
    store.submitProposal({
      path: 'same.ts',
      sourceContent: 'one',
      before: 'one',
      after: 'two',
      instruction: 'first',
      selection: { from: 0, to: 3, selectedText: 'one' },
    });
    store.submitProposal({
      path: 'same.ts',
      sourceContent: 'one',
      before: 'one',
      after: 'three',
      instruction: 'second',
      selection: { from: 0, to: 3, selectedText: 'one' },
    });

    const proposals = useProposedEditStore.getState().proposals;
    expect(proposals).toHaveLength(1);
    expect(proposals[0]?.instruction).toBe('second');
    expect(proposals[0]?.after).toBe('three');
  });

  it('acceptProposal removes by id (caller persists side-effects)', () => {
    useProposedEditStore.getState().submitProposal({
      path: 'c.ts',
      sourceContent: 'x',
      before: 'x',
      after: 'y',
      instruction: 'replace',
      selection: { from: 0, to: 1, selectedText: 'x' },
    });
    const id = useProposedEditStore.getState().proposals[0]?.id ?? '';
    useProposedEditStore.getState().acceptProposal(id);
    expect(useProposedEditStore.getState().proposals).toEqual([]);
  });

  it('clearProposals empties the queue', () => {
    useProposedEditStore.getState().submitProposal({
      path: 'd.ts',
      sourceContent: '',
      before: '',
      after: '',
      instruction: 'noop',
      selection: { from: 0, to: 0, selectedText: '' },
    });
    useProposedEditStore.getState().submitProposal({
      path: 'e.ts',
      sourceContent: '',
      before: '',
      after: '',
      instruction: 'noop',
      selection: { from: 0, to: 0, selectedText: '' },
    });
    useProposedEditStore.getState().clearProposals();
    expect(useProposedEditStore.getState().proposals).toEqual([]);
  });
});
