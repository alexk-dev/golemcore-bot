import { create } from 'zustand';
import { createUuid } from '../utils/uuid';

export interface ProposedEdit {
  id: string;
  path: string;
  before: string;
  after: string;
}

export interface ProposedEditInput {
  path: string;
  before: string;
  after: string;
}

interface ProposedEditState {
  proposals: ProposedEdit[];
  submitProposal: (input: ProposedEditInput) => string;
  acceptProposal: (id: string) => void;
  dismissProposal: (id: string) => void;
  clearProposals: () => void;
}

/**
 * Client-side store for assistant-proposed file edits awaiting user review.
 */
export const useProposedEditStore = create<ProposedEditState>((set) => ({
  proposals: [],

  submitProposal: (input: ProposedEditInput): string => {
    const id = createUuid();
    set((state) => ({
      proposals: [...state.proposals, { id, ...input }],
    }));
    return id;
  },

  acceptProposal: (id: string): void => {
    set((state) => ({
      proposals: state.proposals.filter((proposal) => proposal.id !== id),
    }));
  },

  dismissProposal: (id: string): void => {
    set((state) => ({
      proposals: state.proposals.filter((proposal) => proposal.id !== id),
    }));
  },

  clearProposals: (): void => {
    set({ proposals: [] });
  },
}));
