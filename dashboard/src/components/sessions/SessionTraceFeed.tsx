import { useMemo, useState, type ReactElement } from 'react';
import { Alert, Badge, Card } from '../ui/tailwind-components';

import type { MessageInfo, SessionTrace } from '../../api/sessions';
import {
  buildSessionTraceFeed,
  type SessionTraceFeedMeta,
  type SessionTraceFeedSpanItem,
} from '../../lib/sessionTraceFeed';
import { formatTraceTimestamp } from '../../lib/traceFormat';
import { SessionTraceBubble } from './SessionTraceBubble';
import { SessionTraceMetaModal } from './SessionTraceMetaModal';
import { SessionTracePayloadModal } from './SessionTracePayloadModal';

export interface SessionTraceFeedProps {
  messages: MessageInfo[];
  trace: SessionTrace;
}

export function SessionTraceFeed({ messages, trace }: SessionTraceFeedProps): ReactElement {
  const [selectedMeta, setSelectedMeta] = useState<SessionTraceFeedMeta | null>(null);
  const [selectedPayloadItem, setSelectedPayloadItem] = useState<SessionTraceFeedSpanItem | null>(null);
  const feed = useMemo(() => buildSessionTraceFeed(messages, trace), [messages, trace]);

  if (feed.turns.length === 0) {
    return (
      <Alert variant="secondary" className="mb-0">
        No messages or trace spans were available for this session.
      </Alert>
    );
  }

  return (
    <>
      <div className="d-flex flex-column gap-3">
        {feed.turns.map((turn, index) => (
          <Card key={turn.id} className="settings-card">
            <Card.Body>
              <div className="d-flex flex-wrap justify-content-between gap-2 mb-3">
                <div>
                  <h3 className="h6 mb-1">{turn.title} {index + 1}</h3>
                  {turn.timestamp != null && turn.timestamp.length > 0 && (
                    <div className="small text-body-secondary">{formatTraceTimestamp(turn.timestamp)}</div>
                  )}
                </div>
                <div className="d-flex flex-wrap gap-2">
                  {turn.traceNames.map((traceName) => (
                    <Badge key={`${turn.id}:${traceName}`} bg="secondary">
                      {traceName}
                    </Badge>
                  ))}
                </div>
              </div>
              <div className="d-flex flex-column gap-3 session-trace-feed">
                {turn.items.map((item) => (
                  <SessionTraceBubble
                    key={item.id}
                    item={item}
                    onOpenMeta={(spanItem) => setSelectedMeta(spanItem.traceMeta)}
                    onOpenPayload={(spanItem) => setSelectedPayloadItem(spanItem)}
                  />
                ))}
              </div>
            </Card.Body>
          </Card>
        ))}
      </div>
      <SessionTraceMetaModal meta={selectedMeta} onHide={() => setSelectedMeta(null)} />
      <SessionTracePayloadModal item={selectedPayloadItem} onHide={() => setSelectedPayloadItem(null)} />
    </>
  );
}
