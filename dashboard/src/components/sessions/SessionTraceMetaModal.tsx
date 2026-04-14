import type { ReactElement } from 'react';
import { ListGroup, Modal } from '../ui/tailwind-components';

import type { SessionTraceFeedMeta } from '../../lib/sessionTraceFeed';

export interface SessionTraceMetaModalProps {
  meta: SessionTraceFeedMeta | null;
  onHide: () => void;
}

export function SessionTraceMetaModal({ meta, onHide }: SessionTraceMetaModalProps): ReactElement {
  return (
    <Modal show={meta != null} onHide={onHide} centered>
      <Modal.Header closeButton>
        <Modal.Title>Trace metadata</Modal.Title>
      </Modal.Header>
      <Modal.Body>
        {meta == null ? null : (
          <ListGroup variant="flush">
            <ListGroup.Item>
              <div className="fw-semibold">traceId</div>
              <div className="small text-break">{meta.traceId}</div>
            </ListGroup.Item>
            <ListGroup.Item>
              <div className="fw-semibold">spanId</div>
              <div className="small text-break">{meta.spanId ?? '-'}</div>
            </ListGroup.Item>
            <ListGroup.Item>
              <div className="fw-semibold">parentSpanId</div>
              <div className="small text-break">{meta.parentSpanId ?? '-'}</div>
            </ListGroup.Item>
            <ListGroup.Item>
              <div className="fw-semibold">source</div>
              <div className="small text-break">{meta.source ?? '-'}</div>
            </ListGroup.Item>
          </ListGroup>
        )}
      </Modal.Body>
    </Modal>
  );
}
