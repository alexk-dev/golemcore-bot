import type { ReactElement } from 'react';
import { Badge, Card } from '../ui/tailwind-components';
import type { WebhookDeliverySummary } from '../../api/webhookDeliveries';
import {
  formatWebhookTimestamp,
  webhookSourceBadgeVariant,
  webhookStatusBadgeVariant,
} from './deliveryUtils';

interface DeliveryTableProps {
  deliveries: WebhookDeliverySummary[];
  selectedDeliveryId: string | null;
  onSelect: (deliveryId: string) => void;
}

export function DeliveryTable({ deliveries, selectedDeliveryId, onSelect }: DeliveryTableProps): ReactElement {
  if (deliveries.length === 0) {
    return (
      <Card className="settings-card mb-3">
        <Card.Body className="text-body-secondary small">
          No delivery attempts yet. Trigger a webhook with callback URL or send a test callback.
        </Card.Body>
      </Card>
    );
  }

  return (
    <Card className="settings-card mb-3">
      <Card.Body>
        <div className="table-responsive">
          <table className="table table-sm align-middle dashboard-table responsive-table mb-0">
            <thead>
              <tr>
                <th scope="col">Run</th>
                <th scope="col">Status</th>
                <th scope="col">Source</th>
                <th scope="col">Attempts</th>
                <th scope="col">Updated</th>
              </tr>
            </thead>
            <tbody>
              {deliveries.map((delivery) => {
                const isSelected = delivery.deliveryId === selectedDeliveryId;
                return (
                  <tr
                    key={delivery.deliveryId}
                    className={isSelected ? 'table-active' : ''}
                    role="button"
                    tabIndex={0}
                    onClick={() => onSelect(delivery.deliveryId)}
                    onKeyDown={(event) => {
                      if (event.key === 'Enter' || event.key === ' ') {
                        event.preventDefault();
                        onSelect(delivery.deliveryId);
                      }
                    }}
                  >
                    <td data-label="Run">
                      <div className="fw-semibold">{delivery.runId ?? '—'}</div>
                      <div className="small text-body-secondary">
                        <code>{delivery.deliveryId.slice(0, 8)}</code>
                      </div>
                    </td>
                    <td data-label="Status">
                      <Badge bg={webhookStatusBadgeVariant(delivery.status)}>{delivery.status}</Badge>
                    </td>
                    <td data-label="Source">
                      <Badge bg={webhookSourceBadgeVariant(delivery.source)}>{delivery.source}</Badge>
                    </td>
                    <td data-label="Attempts">{delivery.attempts}</td>
                    <td data-label="Updated" className="small text-body-secondary">
                      {formatWebhookTimestamp(delivery.updatedAt)}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      </Card.Body>
    </Card>
  );
}
