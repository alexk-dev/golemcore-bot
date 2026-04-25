import { create } from 'zustand';
import { createUuid } from '../utils/uuid';

export interface ProposedEditSelection {
  from: number;
  to: number;
  selectedText: string;
}

export interface ProposedEdit {
  id: string;
  path: string;
  sourceContent: string;
  before: string;
  after: string;
  instruction: string;
  selection: ProposedEditSelection;
}

export interface ProposedEditInput {
  path: string;
  sourceContent: string;
  before: string;
  after: string;
  instruction: string;
  selection: ProposedEditSelection;
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
      proposals: [...state.proposals.filter((proposal) => proposal.path !== input.path), { id, ...input }],
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
