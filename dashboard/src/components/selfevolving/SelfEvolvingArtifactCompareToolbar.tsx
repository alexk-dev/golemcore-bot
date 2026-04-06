import type { ReactElement } from 'react';
import { Button, ButtonGroup, Card, Form } from 'react-bootstrap';

import type { SelfEvolvingArtifactCompareOptions } from '../../api/selfEvolving';

export type SelfEvolvingArtifactCompareMode = 'revision' | 'transition';

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
  const formatOptionLabel = (label: string): string => label.split('_').join(' ');

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
          <div className="d-flex flex-column gap-2">
            <Form.Select
              value={`${selectedFromRevisionId ?? ''}::${selectedToRevisionId ?? ''}`}
              onChange={(event) => {
                const [fromRevisionId, toRevisionId] = event.target.value.split('::');
                onSelectRevisionPair(fromRevisionId, toRevisionId);
              }}
            >
              {(compareOptions?.revisionOptions ?? []).map((option) => (
                <option key={`${option.fromId}-${option.toId}`} value={`${option.fromId}::${option.toId}`}>
                  {formatOptionLabel(option.label)}
                </option>
              ))}
            </Form.Select>
            <div className="small text-body-secondary">
              {selectedFromRevisionId ?? 'n/a'} → {selectedToRevisionId ?? 'n/a'}
            </div>
          </div>
        ) : (
          <div className="d-flex flex-column gap-2">
            <Form.Select
              value={`${selectedFromNodeId ?? ''}::${selectedToNodeId ?? ''}`}
              onChange={(event) => {
                const [fromNodeId, toNodeId] = event.target.value.split('::');
                onSelectTransitionPair(fromNodeId, toNodeId);
              }}
            >
              {(compareOptions?.transitionOptions ?? []).map((option) => (
                <option key={`${option.fromId}-${option.toId}`} value={`${option.fromId}::${option.toId}`}>
                  {formatOptionLabel(option.label)}
                </option>
              ))}
            </Form.Select>
            <div className="small text-body-secondary">
              {selectedFromNodeId ?? 'n/a'} → {selectedToNodeId ?? 'n/a'}
            </div>
          </div>
        )}
      </Card.Body>
    </Card>
  );
}
