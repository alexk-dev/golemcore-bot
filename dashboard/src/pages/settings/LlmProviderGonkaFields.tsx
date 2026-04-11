import type { ReactElement } from 'react';
import { Button, Col, Form, Row } from 'react-bootstrap';

import type { LlmProviderConfig } from '../../api/settingsTypes';
import { toNullableString } from './llmProvidersSupport';

export interface LlmProviderGonkaFieldsProps {
  form: LlmProviderConfig;
  onFormChange: (form: LlmProviderConfig) => void;
}

export function LlmProviderGonkaFields({ form, onFormChange }: LlmProviderGonkaFieldsProps): ReactElement {
  const endpointsText = serializeEndpoints(form.endpoints);

  return (
    <Col md={12}>
      <div className="border rounded-3 p-3 mt-2 bg-body-tertiary">
        <Row className="g-2">
          <Col md={6}>
            <Form.Group className="mb-2">
              <Form.Label className="small fw-medium">Gonka Source URL</Form.Label>
              <Form.Control
                size="sm"
                type="url"
                value={form.sourceUrl ?? ''}
                onChange={(event) => onFormChange({ ...form, sourceUrl: toNullableString(event.target.value) })}
                placeholder="https://node3.gonka.ai"
              />
              <Form.Text className="text-body-secondary">
                Used to discover active transfer-agent endpoints when no explicit endpoints are set.
              </Form.Text>
            </Form.Group>
          </Col>
          <Col md={6}>
            <Form.Group className="mb-2">
              <Form.Label className="small fw-medium">Requester Address</Form.Label>
              <Form.Control
                size="sm"
                value={form.gonkaAddress ?? ''}
                onChange={(event) => onFormChange({ ...form, gonkaAddress: toNullableString(event.target.value) })}
                placeholder="Optional; derived from private key when empty"
              />
            </Form.Group>
          </Col>
          <Col md={12}>
            <Form.Group className="mb-2">
              <Form.Label className="small fw-medium">Explicit Endpoints</Form.Label>
              <Form.Control
                as="textarea"
                rows={2}
                size="sm"
                value={endpointsText}
                onChange={(event) => onFormChange({ ...form, endpoints: parseEndpoints(event.target.value) })}
                placeholder="https://host/v1;gonka1transferaddress"
              />
              <Form.Text className="text-body-secondary">
                Optional comma/newline-separated pairs in url;transferAddress format. Overrides Source URL discovery.
              </Form.Text>
            </Form.Group>
          </Col>
          <Col md={12}>
            <Button
              type="button"
              variant="secondary"
              size="sm"
              onClick={() => onFormChange({ ...form, sourceUrl: 'https://node3.gonka.ai' })}
            >
              Use public node
            </Button>
          </Col>
        </Row>
      </div>
    </Col>
  );
}

function serializeEndpoints(endpoints: LlmProviderConfig['endpoints']): string {
  return (endpoints ?? [])
    .map((endpoint) => `${endpoint.url};${endpoint.transferAddress}`)
    .join('\n');
}

function parseEndpoints(value: string): LlmProviderConfig['endpoints'] {
  return value
    .split(/[\n,]/)
    .map((entry) => entry.trim())
    .filter((entry) => entry.length > 0)
    .flatMap((entry) => {
      const parts = entry.split(';');
      if (parts.length !== 2) {
        return [];
      }
      const url = parts[0].trim();
      const transferAddress = parts[1].trim();
      return url.length > 0 && transferAddress.length > 0 ? [{ url, transferAddress }] : [];
    });
}
