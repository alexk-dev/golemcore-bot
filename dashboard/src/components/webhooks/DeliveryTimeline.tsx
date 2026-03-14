import type { ReactElement } from 'react';
import { Badge } from 'react-bootstrap';
import type { WebhookDeliveryEvent } from '../../api/webhookDeliveries';
import {
  formatWebhookTimestamp,
  webhookStatusBadgeVariant,
} from './deliveryUtils';

interface DeliveryTimelineProps {
  events: WebhookDeliveryEvent[];
}

export function DeliveryTimeline({ events }: DeliveryTimelineProps): ReactElement {
  if (events.length === 0) {
    return <div className="small text-body-secondary">No timeline events captured.</div>;
  }

  return (
    <div className="d-flex flex-column gap-2">
      {events.map((event) => (
        <div key={`${event.sequence}-${event.type}`} className="border rounded p-2 small">
          <div className="d-flex align-items-center justify-content-between">
            <div className="d-flex align-items-center gap-2">
              <Badge bg={webhookStatusBadgeVariant(event.status)}>{event.status}</Badge>
              <span className="fw-semibold">{event.type}</span>
            </div>
            <span className="text-body-secondary">{formatWebhookTimestamp(event.timestamp)}</span>
          </div>
          <div className="text-body-secondary mt-1">
            {event.attempt != null && <>attempt {event.attempt} · </>}
            {event.message ?? 'No message'}
          </div>
        </div>
      ))}
    </div>
  );
}
