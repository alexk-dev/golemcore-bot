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
      before: 'const x = 1;\n',
      after: 'const x = 2;\n',
    });
    const { proposals } = useProposedEditStore.getState();
    expect(proposals).toHaveLength(1);
    expect(proposals[0]?.path).toBe('src/foo.ts');
    expect(proposals[0]?.before).toBe('const x = 1;\n');
    expect(proposals[0]?.after).toBe('const x = 2;\n');
    expect(typeof proposals[0]?.id).toBe('string');
    expect(proposals[0]?.id.length).toBeGreaterThan(0);
  });

  it('dismissProposal removes by id', () => {
    const store = useProposedEditStore.getState();
    store.submitProposal({ path: 'a.ts', before: '', after: 'hello' });
    store.submitProposal({ path: 'b.ts', before: '', after: 'world' });

    const targetId = useProposedEditStore.getState().proposals[0]?.id ?? '';
    useProposedEditStore.getState().dismissProposal(targetId);

    const remaining = useProposedEditStore.getState().proposals;
    expect(remaining).toHaveLength(1);
    expect(remaining[0]?.path).toBe('b.ts');
  });

  it('acceptProposal removes by id (caller persists side-effects)', () => {
    useProposedEditStore.getState().submitProposal({ path: 'c.ts', before: 'x', after: 'y' });
    const id = useProposedEditStore.getState().proposals[0]?.id ?? '';
    useProposedEditStore.getState().acceptProposal(id);
    expect(useProposedEditStore.getState().proposals).toEqual([]);
  });

  it('clearProposals empties the queue', () => {
    useProposedEditStore.getState().submitProposal({ path: 'd.ts', before: '', after: '' });
    useProposedEditStore.getState().submitProposal({ path: 'e.ts', before: '', after: '' });
    useProposedEditStore.getState().clearProposals();
    expect(useProposedEditStore.getState().proposals).toEqual([]);
  });
});
