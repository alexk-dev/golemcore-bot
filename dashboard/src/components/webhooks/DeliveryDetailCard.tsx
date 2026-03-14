import type { ReactElement } from 'react';
import { Alert, Badge, Button, Card, Col, Row, Spinner } from 'react-bootstrap';
import type { WebhookDeliveryDetail } from '../../api/webhookDeliveries';
import {
  webhookStatusBadgeVariant,
} from './deliveryUtils';
import { DeliveryTimeline } from './DeliveryTimeline';

interface DeliveryDetailCardProps {
  detail: WebhookDeliveryDetail | undefined;
  loading: boolean;
  onRetry: () => void;
  retryPending: boolean;
}

export function DeliveryDetailCard({ detail, loading, onRetry, retryPending }: DeliveryDetailCardProps): ReactElement {
  if (loading) {
    return (
      <Card className="settings-card">
        <Card.Body className="d-flex align-items-center gap-2 text-body-secondary">
          <Spinner size="sm" />
          <span>Loading delivery detail...</span>
        </Card.Body>
      </Card>
    );
  }

  if (detail == null) {
    return <></>;
  }

  const retryableStatus = detail.status === 'SUCCESS' || detail.status === 'FAILED';
  const retryDisabled = !retryableStatus || detail.callbackUrl == null || detail.payload.status == null || retryPending;

  return (
    <Card className="settings-card">
      <Card.Body>
        <div className="d-flex flex-wrap justify-content-between align-items-start gap-2 mb-3">
          <div>
            <h5 className="mb-1">Delivery detail</h5>
            <div className="small text-body-secondary">
              <code>{detail.deliveryId}</code>
            </div>
          </div>
          <Button type="button" size="sm" variant="primary" onClick={onRetry} disabled={retryDisabled}>
            {retryPending ? 'Retrying...' : 'Retry delivery'}
          </Button>
        </div>

        <Row className="g-3 mb-3">
          <Col md={6}>
            <div className="small text-body-secondary">Callback URL</div>
            <div className="fw-semibold text-break">{detail.callbackUrl ?? '—'}</div>
          </Col>
          <Col md={3}>
            <div className="small text-body-secondary">Status</div>
            <Badge bg={webhookStatusBadgeVariant(detail.status)}>{detail.status}</Badge>
          </Col>
          <Col md={3}>
            <div className="small text-body-secondary">Attempts</div>
            <div className="fw-semibold">{detail.attempts}</div>
          </Col>
        </Row>

        {detail.lastError != null && detail.lastError.length > 0 && (
          <Alert variant="danger" className="small mb-3">
            {detail.lastError}
          </Alert>
        )}

        <Row className="g-3 mb-3">
          <Col md={6}>
            <div className="small text-body-secondary">Payload status</div>
            <div className="fw-semibold">{detail.payload.status ?? '—'}</div>
          </Col>
          <Col md={6}>
            <div className="small text-body-secondary">Payload model</div>
            <div className="fw-semibold">{detail.payload.model ?? '—'}</div>
          </Col>
          <Col md={6}>
            <div className="small text-body-secondary">Payload duration</div>
            <div className="fw-semibold">{detail.payload.durationMs} ms</div>
          </Col>
          <Col md={6}>
            <div className="small text-body-secondary">Payload error</div>
            <div className="fw-semibold text-break">{detail.payload.error ?? '—'}</div>
          </Col>
          <Col md={12}>
            <div className="small text-body-secondary">Payload response</div>
            <pre className="small border rounded p-2 mb-0 bg-body-tertiary">{detail.payload.response ?? '—'}</pre>
          </Col>
        </Row>

        <div className="small text-body-secondary fw-semibold mb-2">Timeline</div>
        <DeliveryTimeline events={detail.events} />
      </Card.Body>
    </Card>
  );
}
