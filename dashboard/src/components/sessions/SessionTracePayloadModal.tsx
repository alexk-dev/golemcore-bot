import type { ReactElement } from 'react';
import { Modal } from '../ui/tailwind-components';

import type { SessionTraceFeedSpanItem } from '../../lib/sessionTraceFeed';
import { SessionTraceSnapshotViewer } from './SessionTraceSnapshotViewer';

export interface SessionTracePayloadModalProps {
  item: SessionTraceFeedSpanItem | null;
  onHide: () => void;
}

export function SessionTracePayloadModal({ item, onHide }: SessionTracePayloadModalProps): ReactElement {
  return (
    <Modal show={item != null} onHide={onHide} size="lg" centered>
      <Modal.Header closeButton>
        <Modal.Title>Payload inspect</Modal.Title>
      </Modal.Header>
      <Modal.Body>
        <SessionTraceSnapshotViewer span={item == null ? null : {
          spanId: item.traceMeta?.spanId ?? item.id,
          parentSpanId: item.traceMeta?.parentSpanId ?? null,
          name: item.title,
          kind: item.bubbleKind.toUpperCase(),
          statusCode: null,
          statusMessage: null,
          startedAt: item.timestamp,
          endedAt: item.timestamp,
          durationMs: null,
          attributes: {},
          events: [],
          snapshots: item.snapshots,
        }} />
      </Modal.Body>
    </Modal>
  );
}
