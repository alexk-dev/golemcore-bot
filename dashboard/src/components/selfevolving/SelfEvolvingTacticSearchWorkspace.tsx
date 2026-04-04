import type { ReactElement } from 'react';
import { Button, Card, Col, Form, Row } from 'react-bootstrap';

import type { SelfEvolvingTacticSearchResponse } from '../../api/selfEvolving';
import { SelfEvolvingTacticDetailPanel } from './SelfEvolvingTacticDetailPanel';
import { SelfEvolvingTacticResultsList } from './SelfEvolvingTacticResultsList';
import { SelfEvolvingTacticSearchStatusBanner } from './SelfEvolvingTacticSearchStatusBanner';
import { SelfEvolvingTacticWhyPanel } from './SelfEvolvingTacticWhyPanel';

interface Props {
  query: string;
  onQueryChange: (query: string) => void;
  searchResponse: SelfEvolvingTacticSearchResponse | null;
  selectedTacticId: string | null;
  onSelectTacticId: (tacticId: string) => void;
  onBackToResults: () => void;
}

export function SelfEvolvingTacticSearchWorkspace({
  query,
  onQueryChange,
  searchResponse,
  selectedTacticId,
  onSelectTacticId,
  onBackToResults,
}: Props): ReactElement {
  const results = searchResponse?.results ?? [];
  const selected = results.find((result) => result.tacticId === selectedTacticId) ?? results[0] ?? null;
  const isDetailMode = selectedTacticId != null && selected != null;

  return (
    <Card>
      <Card.Body>
        {!isDetailMode && (
          <>
            <Card.Title className="h5">Tactic Search</Card.Title>
            <Form.Group controlId="tactic-search-query" className="mb-3">
              <Form.Control
                name="tactic-search-query"
                type="search"
                value={query}
                placeholder="planner, tool routing, failure recovery"
                onChange={(event) => onQueryChange(event.currentTarget.value)}
              />
            </Form.Group>
            <SelfEvolvingTacticSearchStatusBanner status={searchResponse?.status ?? null} />
            <SelfEvolvingTacticResultsList
              results={results}
              selectedTacticId={selectedTacticId}
              onSelectTacticId={onSelectTacticId}
            />
          </>
        )}

        {isDetailMode && (
          <>
            <div className="d-flex flex-wrap align-items-center justify-content-between gap-2 mb-3">
              <Card.Title className="h5 mb-0">{selected.title ?? selected.tacticId}</Card.Title>
              <Button variant="secondary" size="sm" onClick={onBackToResults}>
                Back to results
              </Button>
            </div>
            <Row className="g-3">
              <Col xl={5}>
                <SelfEvolvingTacticDetailPanel tactic={selected} />
              </Col>
              <Col xl={7}>
                <SelfEvolvingTacticWhyPanel
                  explanation={selected.explanation ?? null}
                  successRate={selected.successRate}
                  benchmarkWinRate={selected.benchmarkWinRate}
                  regressionFlags={selected.regressionFlags}
                  promotionState={selected.promotionState}
                  recencyScore={selected.recencyScore}
                  golemLocalUsageSuccess={selected.golemLocalUsageSuccess}
                />
              </Col>
            </Row>
          </>
        )}
      </Card.Body>
    </Card>
  );
}
