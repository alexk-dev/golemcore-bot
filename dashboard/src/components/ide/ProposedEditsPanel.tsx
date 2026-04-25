import { useCallback, type ReactElement } from 'react';
import toast from 'react-hot-toast';
import { InlineDiffView } from './InlineDiffView';
import { useIdeStore } from '../../store/ideStore';
import { useProposedEditStore, type ProposedEdit } from '../../store/proposedEditStore';

/**
 * Panel that renders assistant-proposed file edits above the IDE editor surface.
 */
export function ProposedEditsPanel(): ReactElement | null {
  const proposals = useProposedEditStore((state) => state.proposals);
  const acceptProposal = useProposedEditStore((state) => state.acceptProposal);
  const dismissProposal = useProposedEditStore((state) => state.dismissProposal);
  const activePath = useIdeStore((state) => state.activePath);

  const handleAccept = useCallback(
    (id: string): void => {
      const store = useProposedEditStore.getState();
      const proposal = store.proposals.find((entry) => entry.id === id);
      if (proposal) {
        if (!applyProposalToIdeTab(proposal)) {
          toast.error('File changed since the proposal was created. Retry inline edit.');
          return;
        }
      }
      acceptProposal(id);
    },
    [acceptProposal],
  );

  const handleReject = useCallback(
    (id: string): void => {
      dismissProposal(id);
    },
    [dismissProposal],
  );

  const activeProposal = proposals.find((proposal) => proposal.path === activePath) ?? null;
  if (activeProposal == null) {
    return null;
  }

  return (
    <div className="proposed-edits-panel" role="region" aria-label="Agent proposed edits">
      <InlineDiffView
        proposal={activeProposal}
        onAccept={handleAccept}
        onReject={handleReject}
      />
    </div>
  );
}

function applyProposalToIdeTab(proposal: ProposedEdit): boolean {
  const ide = useIdeStore.getState();
  const existing = ide.openedTabs.find((tab) => tab.path === proposal.path);
  if (!existing) {
    return false;
  }

  if (existing.content === proposal.sourceContent) {
    const nextContent = proposal.sourceContent.slice(0, proposal.selection.from)
      + proposal.after
      + proposal.sourceContent.slice(proposal.selection.to);
    ide.updateTabContent(proposal.path, nextContent);
    return true;
  }

  const currentSelection = existing.content.slice(proposal.selection.from, proposal.selection.to);
  if (currentSelection !== proposal.selection.selectedText) {
    return false;
  }

  const nextContent = existing.content.slice(0, proposal.selection.from)
    + proposal.after
    + existing.content.slice(proposal.selection.to);
  ide.updateTabContent(proposal.path, nextContent);
  return true;
}
