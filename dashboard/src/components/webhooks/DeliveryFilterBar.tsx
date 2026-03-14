import type { ReactElement } from 'react';
import { Button, Card, Form } from 'react-bootstrap';
import type { WebhookDeliveryStatus } from '../../api/webhookDeliveries';
import {
  DELIVERY_STATUSES,
  normalizeDeliveryLimit,
} from './deliveryUtils';

interface DeliveryFilterBarProps {
  status: 'ALL' | WebhookDeliveryStatus;
  limit: number;
  onStatusChange: (status: 'ALL' | WebhookDeliveryStatus) => void;
  onLimitChange: (limit: number) => void;
  onRefresh: () => void;
  refreshPending: boolean;
}

export function DeliveryFilterBar({
  status,
  limit,
  onStatusChange,
  onLimitChange,
  onRefresh,
  refreshPending,
}: DeliveryFilterBarProps): ReactElement {
  return (
    <Card className="settings-card mb-3">
      <Card.Body>
        <div className="d-flex flex-wrap align-items-end gap-2">
          <Form.Group>
            <Form.Label className="small fw-medium">Status</Form.Label>
            <Form.Select
              size="sm"
              value={status}
              onChange={(event) => onStatusChange(event.target.value as 'ALL' | WebhookDeliveryStatus)}
            >
              {DELIVERY_STATUSES.map((entry) => (
                <option key={entry} value={entry}>{entry}</option>
              ))}
            </Form.Select>
          </Form.Group>

          <Form.Group>
            <Form.Label className="small fw-medium">Limit</Form.Label>
            <Form.Control
              size="sm"
              type="number"
              value={limit}
              min={1}
              max={200}
              onChange={(event) => onLimitChange(normalizeDeliveryLimit(event.target.value))}
            />
          </Form.Group>

          <div className="d-flex align-items-center gap-2 ms-auto">
            <Button
              type="button"
              size="sm"
              variant="secondary"
              onClick={onRefresh}
              disabled={refreshPending}
            >
              Refresh
            </Button>
          </div>
        </div>
      </Card.Body>
    </Card>
  );
}
