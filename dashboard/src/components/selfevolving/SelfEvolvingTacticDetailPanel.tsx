import { type ReactElement, useState } from 'react';

import type { SelfEvolvingTacticSearchResult } from '../../api/selfEvolving';
import ConfirmModal from '../common/ConfirmModal';
import { humanizeStatus } from './selfEvolvingUi';

interface Props {
  tactic: SelfEvolvingTacticSearchResult | null;
  onDeactivateTactic: (tacticId: string) => void;
  onDeleteTactic: (tacticId: string) => void;
  isDeactivating: boolean;
  isDeleting: boolean;
}

export function SelfEvolvingTacticDetailPanel({
  tactic,
  onDeactivateTactic,
  onDeleteTactic,
  isDeactivating,
  isDeleting,
}: Props): ReactElement {
  const [showDeleteConfirm, setShowDeleteConfirm] = useState(false);

  if (tactic == null) {
    return (
      <div className="card card-body">
        <p className="text-sm text-muted-foreground">Select a tactic to see its details.</p>
      </div>
    );
  }

  return (
    <>
      <div className="card card-body">
        <h6 className="text-sm font-semibold mb-3">{tactic.title ?? tactic.tacticId}</h6>
        <div className="flex flex-col gap-3 text-sm">
          <div>
            <span className="text-xs font-medium uppercase tracking-wider text-muted-foreground" title="What problem or task this tactic addresses">Intent</span>
            <p className="mt-0.5 mb-0">{tactic.intentSummary ?? 'n/a'}</p>
          </div>
          <div>
            <span className="text-xs font-medium uppercase tracking-wider text-muted-foreground" title="How the agent behaves when this tactic is active">Behavior</span>
            <p className="mt-0.5 mb-0">{tactic.behaviorSummary ?? 'n/a'}</p>
          </div>
          <div>
            <span className="text-xs font-medium uppercase tracking-wider text-muted-foreground" title="Which tools the tactic makes available to the agent">Tools</span>
            <p className="mt-0.5 mb-0">{tactic.toolSummary ?? 'n/a'}</p>
          </div>
          <div>
            <span className="text-xs font-medium uppercase tracking-wider text-muted-foreground" title="Expected result when the tactic is applied">Outcome</span>
            <p className="mt-0.5 mb-0">{tactic.outcomeSummary ?? 'n/a'}</p>
          </div>
          <div>
            <span className="text-xs font-medium uppercase tracking-wider text-muted-foreground">Promotion state</span>
            <p className="mt-0.5 mb-0">{humanizeStatus(tactic.promotionState)}</p>
          </div>
          <div>
            <span className="text-xs font-medium uppercase tracking-wider text-muted-foreground" title="Traces and campaigns that contributed evidence for this tactic">Evidence</span>
            <p className="mt-0.5 mb-0 font-mono text-xs">{tactic.evidenceSnippets.join(', ') || 'n/a'}</p>
          </div>
          <div className="flex flex-wrap gap-2 pt-2">
            <button
              type="button"
              className="btn btn-secondary btn-sm"
              onClick={() => onDeactivateTactic(tactic.tacticId)}
              disabled={isDeactivating || isDeleting || tactic.promotionState === 'inactive'}
            >
              {isDeactivating ? 'Deactivating...' : 'Deactivate'}
            </button>
            <button
              type="button"
              className="btn btn-danger btn-sm"
              onClick={() => setShowDeleteConfirm(true)}
              disabled={isDeleting || isDeactivating}
            >
              {isDeleting ? 'Deleting...' : 'Delete'}
            </button>
          </div>
        </div>
      </div>
      <ConfirmModal
        show={showDeleteConfirm}
        title="Delete tactic"
        message={`Delete "${tactic.title ?? tactic.tacticId}"? This removes the tactic from local storage and the search index.`}
        confirmLabel="Delete"
        confirmVariant="danger"
        isProcessing={isDeleting}
        onConfirm={() => {
          onDeleteTactic(tactic.tacticId);
          setShowDeleteConfirm(false);
        }}
        onCancel={() => setShowDeleteConfirm(false)}
      />
    </>
  );
}
