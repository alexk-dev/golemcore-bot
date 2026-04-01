import type { ReactElement } from 'react';
import { Badge, Button, Card } from 'react-bootstrap';

import type { SelfEvolvingTacticSearchResult } from '../../api/selfEvolving';

interface Props {
  results: SelfEvolvingTacticSearchResult[];
  selectedTacticId: string | null;
  onSelectTacticId: (tacticId: string) => void;
}

export function SelfEvolvingTacticResultsList({ results, selectedTacticId, onSelectTacticId }: Props): ReactElement {
  return (
    <Card className="mb-3">
      <Card.Body>
        <Card.Title className="h6">Results</Card.Title>
        <div className="d-flex flex-column gap-2">
          {results.map((result) => (
            <div key={result.tacticId} className="border rounded p-2">
              <div className="d-flex justify-content-between align-items-start gap-2">
                <div>
                  <div className="fw-semibold">{result.title ?? result.tacticId}</div>
                  <div className="text-body-secondary small">{result.artifactKey}</div>
                </div>
                <Button
                  size="sm"
                  variant={selectedTacticId === result.tacticId ? 'primary' : 'secondary'}
                  onClick={() => onSelectTacticId(result.tacticId)}
                >
                  Inspect
                </Button>
              </div>
              <div className="d-flex flex-wrap gap-2 mt-2">
                <Badge bg="secondary">Success rate {formatPercent(result.successRate)}</Badge>
                <Badge bg="secondary">Benchmark win rate {formatPercent(result.benchmarkWinRate)}</Badge>
                <Badge bg="secondary">Regression flags {result.regressionFlags.length}</Badge>
                <Badge bg="secondary">Recency {formatNumber(result.recencyScore)}</Badge>
                <Badge bg="secondary">Golem-local usage success {formatPercent(result.golemLocalUsageSuccess)}</Badge>
              </div>
            </div>
          ))}
        </div>
      </Card.Body>
    </Card>
  );
}

function formatPercent(value: number | null): string {
  return value == null ? 'n/a' : `${Math.round(value * 100)}%`;
}

function formatNumber(value: number | null): string {
  return value == null ? 'n/a' : value.toFixed(2);
}
