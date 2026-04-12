import type { ReactElement } from 'react';

interface SelfEvolvingTacticActionsProps {
  tacticId: string;
  promotionState: string | null;
  isDeactivating: boolean;
  isReactivating: boolean;
  isDeleting: boolean;
  onDeactivateTactic: (tacticId: string) => void;
  onReactivateTactic: (tacticId: string) => void;
  onRequestDelete: () => void;
}

export function SelfEvolvingTacticActions({
  tacticId,
  promotionState,
  isDeactivating,
  isReactivating,
  isDeleting,
  onDeactivateTactic,
  onReactivateTactic,
  onRequestDelete,
}: SelfEvolvingTacticActionsProps): ReactElement {
  const isInactive = promotionState === 'inactive';
  return (
    <div className="flex flex-wrap gap-2 pt-2">
      {isInactive ? (
        <button
          type="button"
          className="btn btn-primary btn-sm"
          onClick={() => onReactivateTactic(tacticId)}
          disabled={isReactivating || isDeleting}
        >
          {isReactivating ? 'Reactivating...' : 'Reactivate'}
        </button>
      ) : (
        <button
          type="button"
          className="btn btn-secondary btn-sm"
          onClick={() => onDeactivateTactic(tacticId)}
          disabled={isDeactivating || isDeleting}
        >
          {isDeactivating ? 'Deactivating...' : 'Deactivate'}
        </button>
      )}
      <button
        type="button"
        className="btn btn-danger btn-sm"
        onClick={onRequestDelete}
        disabled={isDeleting || isDeactivating}
      >
        {isDeleting ? 'Deleting...' : 'Delete'}
      </button>
    </div>
  );
}
