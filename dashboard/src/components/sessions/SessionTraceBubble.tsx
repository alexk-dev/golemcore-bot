import type { ReactElement } from 'react';
import { Badge, Button } from '../ui/tailwind-components';

import type {
  SessionTraceFeedItem,
  SessionTraceFeedMessageItem,
  SessionTraceFeedSpanItem,
} from '../../lib/sessionTraceFeed';
import { formatTraceTimestamp } from '../../lib/traceFormat';

export interface SessionTraceBubbleProps {
  item: SessionTraceFeedItem;
  onOpenMeta: (item: SessionTraceFeedSpanItem) => void;
  onOpenPayload: (item: SessionTraceFeedSpanItem) => void;
}

function getBubbleClass(item: SessionTraceFeedItem): string {
  if (item.type === 'message') {
    if (item.role === 'user') {
      return 'session-trace-bubble session-trace-bubble-user';
    }
    if (item.role === 'assistant') {
      return 'session-trace-bubble session-trace-bubble-assistant';
    }
    return 'session-trace-bubble session-trace-bubble-tool';
  }
  return 'session-trace-bubble session-trace-bubble-service';
}

function renderMessageBody(item: SessionTraceFeedMessageItem): ReactElement {
  return <div className="session-trace-bubble-content">{item.content}</div>;
}

function renderSpanBody(
  item: SessionTraceFeedSpanItem,
  onOpenMeta: (item: SessionTraceFeedSpanItem) => void,
  onOpenPayload: (item: SessionTraceFeedSpanItem) => void,
): ReactElement {
  return (
    <div className="d-flex flex-column gap-2">
      {item.content != null && <div className="session-trace-bubble-content">{item.content}</div>}
      {item.eventNotes.length > 0 && (
        <div className="d-flex flex-column gap-1">
          {item.eventNotes.map((note) => (
            <div key={note} className="small text-body-secondary">
              {note}
            </div>
          ))}
        </div>
      )}
      <div className="d-flex flex-wrap gap-2">
        <Button type="button" size="sm" variant="secondary" onClick={() => onOpenMeta(item)}>
          Trace meta
        </Button>
        {item.hasPayloadInspect && (
          <Button type="button" size="sm" variant="primary" onClick={() => onOpenPayload(item)}>
            Payload inspect
          </Button>
        )}
      </div>
    </div>
  );
}

export function SessionTraceBubble({
  item,
  onOpenMeta,
  onOpenPayload,
}: SessionTraceBubbleProps): ReactElement {
  return (
    <div className={getBubbleClass(item)}>
      <div className="d-flex flex-wrap justify-content-between gap-2 align-items-start">
        <div>
          <div className="fw-semibold">{item.title}</div>
          {item.timestamp != null && item.timestamp.length > 0 && (
            <div className="session-trace-bubble-meta">{formatTraceTimestamp(item.timestamp)}</div>
          )}
        </div>
      </div>
      <div className="d-flex flex-wrap gap-2 mt-2">
        {item.tags.map((tag) => (
          <Badge key={`${item.id}:${tag.label}`} bg={tag.variant}>
            {tag.label}
          </Badge>
        ))}
      </div>
      <div className="mt-3">
        {item.type === 'message' ? renderMessageBody(item) : renderSpanBody(item, onOpenMeta, onOpenPayload)}
      </div>
    </div>
  );
}
