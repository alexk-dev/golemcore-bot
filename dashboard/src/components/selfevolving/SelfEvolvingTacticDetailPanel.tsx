import type { ReactElement } from 'react';
import { Card } from 'react-bootstrap';

import type { SelfEvolvingTacticSearchResult } from '../../api/selfEvolving';

interface Props {
  tactic: SelfEvolvingTacticSearchResult | null;
}

export function SelfEvolvingTacticDetailPanel({ tactic }: Props): ReactElement {
  if (tactic == null) {
    return (
      <Card className="mb-3">
        <Card.Body>Select a tactic to inspect its detail.</Card.Body>
      </Card>
    );
  }
  return (
    <Card className="mb-3">
      <Card.Body>
        <Card.Title className="h6">{tactic.title ?? tactic.tacticId}</Card.Title>
        <div className="d-flex flex-column gap-2">
          <div><strong>Intent</strong>: {tactic.intentSummary ?? 'n/a'}</div>
          <div><strong>Behavior</strong>: {tactic.behaviorSummary ?? 'n/a'}</div>
          <div><strong>Tools</strong>: {tactic.toolSummary ?? 'n/a'}</div>
          <div><strong>Outcome</strong>: {tactic.outcomeSummary ?? 'n/a'}</div>
          <div><strong>Promotion state</strong>: {tactic.promotionState ?? 'n/a'}</div>
          <div><strong>Evidence</strong>: {tactic.evidenceSnippets.join(', ') || 'n/a'}</div>
        </div>
      </Card.Body>
    </Card>
  );
}
