import type { ReactElement } from 'react';
import { Card, Col, Form, Row } from 'react-bootstrap';

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
  onOpenArtifactStream: (artifactStreamId: string) => void;
}

export function SelfEvolvingTacticSearchWorkspace({
  query,
  onQueryChange,
  searchResponse,
  selectedTacticId,
  onSelectTacticId,
  onOpenArtifactStream,
}: Props): ReactElement {
  const results = searchResponse?.results ?? [];
  const selected = results.find((result) => result.tacticId === selectedTacticId) ?? results[0] ?? null;
  return (
    <Card className="mb-4">
      <Card.Body>
        <Card.Title className="h5">Tactic Search</Card.Title>
        <Form.Group controlId="tactic-search-query" className="mb-3">
          <Form.Label>Search query</Form.Label>
          <Form.Control
            name="tactic-search-query"
            type="search"
            value={query}
            placeholder="planner, tool routing, failure recovery"
            onChange={(event) => onQueryChange(event.currentTarget.value)}
          />
          <Form.Text className="text-body-secondary">
            Search by intent, tools, recovery patterns, and benchmark-backed behavior.
          </Form.Text>
        </Form.Group>
        <SelfEvolvingTacticSearchStatusBanner status={searchResponse?.status ?? null} />
        <Row className="g-3">
          <Col xl={5}>
            <SelfEvolvingTacticResultsList
              results={results}
              selectedTacticId={selectedTacticId}
              onSelectTacticId={onSelectTacticId}
            />
          </Col>
          <Col xl={7}>
            <SelfEvolvingTacticDetailPanel
              tactic={selected}
              onOpenArtifactStream={onOpenArtifactStream}
            />
            <SelfEvolvingTacticWhyPanel
              explanation={selected?.explanation ?? null}
              successRate={selected?.successRate}
              benchmarkWinRate={selected?.benchmarkWinRate}
              regressionFlags={selected?.regressionFlags}
              promotionState={selected?.promotionState}
              recencyScore={selected?.recencyScore}
              golemLocalUsageSuccess={selected?.golemLocalUsageSuccess}
            />
          </Col>
        </Row>
      </Card.Body>
    </Card>
  );
}
