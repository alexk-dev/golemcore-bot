import type { ReactElement } from 'react';
import { Col, Form, Row } from 'react-bootstrap';

import type { UpdateEmbeddings } from './SelfEvolvingTacticSearchEmbeddingsSections';

interface EmbeddingDimensionSettingsProps {
  resolvedDimensions: number;
  resolvedBatchSize: number;
  disabled?: boolean;
  updateEmbeddings: UpdateEmbeddings;
  toNullableInt: (value: string) => number | null;
}

export function EmbeddingDimensionSettings({
  resolvedDimensions,
  resolvedBatchSize,
  disabled = false,
  updateEmbeddings,
  toNullableInt,
}: EmbeddingDimensionSettingsProps): ReactElement {
  return (
    <Row className="g-3 mb-4">
      <Col md={6}>
        <Form.Group controlId="self-evolving-embedding-dimensions">
          <Form.Label className="small fw-medium">Dimensions</Form.Label>
          <Form.Control
            size="sm"
            type="number"
            min={1}
            value={resolvedDimensions}
            disabled={disabled}
            onChange={(event) => updateEmbeddings((current) => ({ ...current, dimensions: toNullableInt(event.target.value) }))}
          />
        </Form.Group>
      </Col>
      <Col md={6}>
        <Form.Group controlId="self-evolving-embedding-batch-size">
          <Form.Label className="small fw-medium">Batch size</Form.Label>
          <Form.Control
            size="sm"
            type="number"
            min={1}
            value={resolvedBatchSize}
            disabled={disabled}
            onChange={(event) => updateEmbeddings((current) => ({ ...current, batchSize: toNullableInt(event.target.value) }))}
          />
        </Form.Group>
      </Col>
    </Row>
  );
}
