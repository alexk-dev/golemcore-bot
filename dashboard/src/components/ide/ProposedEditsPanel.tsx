import { useCallback, type ReactElement } from 'react';
import { InlineDiffView } from './InlineDiffView';
import { useIdeStore } from '../../store/ideStore';
import { useProposedEditStore, type ProposedEdit } from '../../store/proposedEditStore';

export function ProposedEditsPanel(): ReactElement | null {
  const proposals = useProposedEditStore((state) => state.proposals);
  const acceptProposal = useProposedEditStore((state) => state.acceptProposal);
  const dismissProposal = useProposedEditStore((state) => state.dismissProposal);

  const handleAccept = useCallback(
    (id: string): void => {
      const store = useProposedEditStore.getState();
      const proposal = store.proposals.find((entry) => entry.id === id);
      if (proposal) {
        applyProposalToIdeTab(proposal);
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

  if (proposals.length === 0) {
    return null;
  }

  return (
    <div className="proposed-edits-panel" role="region" aria-label="Agent proposed edits">
      {proposals.map((proposal) => (
        <InlineDiffView
          key={proposal.id}
          proposal={proposal}
          onAccept={handleAccept}
          onReject={handleReject}
        />
      ))}
    </div>
  );
}

function applyProposalToIdeTab(proposal: ProposedEdit): void {
  const ide = useIdeStore.getState();
  const existing = ide.openedTabs.find((tab) => tab.path === proposal.path);
  if (!existing) {
    return;
  }
  ide.updateTabContent(proposal.path, proposal.after);
}
