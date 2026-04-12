import type { ReactElement } from 'react';
import { Button, ButtonGroup, Card } from 'react-bootstrap';

import type { SelfEvolvingArtifactCompareOptions } from '../../api/selfEvolving';

import { SelfEvolvingArtifactCompareSelect } from './SelfEvolvingArtifactCompareSelect';
import type { SelfEvolvingArtifactCompareMode } from './SelfEvolvingArtifactCompareTypes';

interface SelfEvolvingArtifactCompareToolbarProps {
  compareMode: SelfEvolvingArtifactCompareMode;
  compareOptions: SelfEvolvingArtifactCompareOptions | null;
  selectedFromRevisionId: string | null;
  selectedToRevisionId: string | null;
  selectedFromNodeId: string | null;
  selectedToNodeId: string | null;
  onSelectCompareMode: (compareMode: SelfEvolvingArtifactCompareMode) => void;
  onSelectRevisionPair: (fromRevisionId: string, toRevisionId: string) => void;
  onSelectTransitionPair: (fromNodeId: string, toNodeId: string) => void;
}

export function SelfEvolvingArtifactCompareToolbar({
  compareMode,
  compareOptions,
  selectedFromRevisionId,
  selectedToRevisionId,
  selectedFromNodeId,
  selectedToNodeId,
  onSelectCompareMode,
  onSelectRevisionPair,
  onSelectTransitionPair,
}: SelfEvolvingArtifactCompareToolbarProps): ReactElement {
  return (
    <Card className="selfevolving-section-card">
      <Card.Body>
        <div className="d-flex flex-wrap align-items-center justify-content-between gap-3 mb-3">
          <div>
            <h5 className="mb-1">Compare Pair</h5>
            <p className="text-body-secondary small mb-0">Switch between revision compare and rollout transition compare without leaving the workspace.</p>
          </div>
          <ButtonGroup aria-label="Compare mode">
            <Button
              type="button"
              variant={compareMode === 'revision' ? 'primary' : 'secondary'}
              onClick={() => onSelectCompareMode('revision')}
            >
              Revisions
            </Button>
            <Button
              type="button"
              variant={compareMode === 'transition' ? 'primary' : 'secondary'}
              onClick={() => onSelectCompareMode('transition')}
            >
              Transitions
            </Button>
          </ButtonGroup>
        </div>

        {compareMode === 'revision' ? (
          <SelfEvolvingArtifactCompareSelect
            fromId={selectedFromRevisionId}
            toId={selectedToRevisionId}
            options={compareOptions?.revisionOptions ?? []}
            onSelectPair={onSelectRevisionPair}
          />
        ) : (
          <SelfEvolvingArtifactCompareSelect
            fromId={selectedFromNodeId}
            toId={selectedToNodeId}
            options={compareOptions?.transitionOptions ?? []}
            onSelectPair={onSelectTransitionPair}
          />
        )}
      </Card.Body>
    </Card>
  );
}
