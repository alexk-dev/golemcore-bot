import { useState } from 'react';
import { Table, Button, Badge, Modal, Spinner, Card, Placeholder } from 'react-bootstrap';
import { useSessions, useSession, useDeleteSession, useCompactSession, useClearSession } from '../hooks/useSessions';
import toast from 'react-hot-toast';
import ConfirmModal from '../components/common/ConfirmModal';

export default function SessionsPage() {
  const { data: sessions, isLoading } = useSessions();
  const deleteMut = useDeleteSession();
  const compactMut = useCompactSession();
  const clearMut = useClearSession();
  const [viewId, setViewId] = useState<string | null>(null);
  const [confirmAction, setConfirmAction] = useState<{ type: 'clear' | 'delete'; sessionId: string } | null>(null);
  const { data: detail } = useSession(viewId ?? '');

  const handleConfirmAction = async () => {
    if (!confirmAction) {
      return;
    }

    try {
      if (confirmAction.type === 'clear') {
        await clearMut.mutateAsync(confirmAction.sessionId);
        toast.success('Cleared');
      } else {
        await deleteMut.mutateAsync(confirmAction.sessionId);
        toast.success('Deleted');
      }
    } finally {
      setConfirmAction(null);
    }
  };

  if (isLoading) {
    return (
      <div>
        <div className="section-header">
          <h4 className="mb-0">Sessions</h4>
        </div>
        <Card>
          <Card.Body>
            <Placeholder as="div" animation="glow" className="mb-2">
              <Placeholder xs={12} />
            </Placeholder>
            <Placeholder as="div" animation="glow" className="mb-2">
              <Placeholder xs={12} />
            </Placeholder>
            <Placeholder as="div" animation="glow" className="mb-2">
              <Placeholder xs={10} />
            </Placeholder>
            <div className="d-flex justify-content-center pt-2">
              <Spinner size="sm" />
            </div>
          </Card.Body>
        </Card>
      </div>
    );
  }

  return (
    <div>
      <div className="section-header">
        <h4 className="mb-0">Sessions</h4>
      </div>
      <Table hover responsive>
        <thead>
          <tr>
            <th>ID</th>
            <th>Channel</th>
            <th>Messages</th>
            <th>State</th>
            <th>Updated</th>
            <th>Actions</th>
          </tr>
        </thead>
        <tbody>
          {sessions?.map((s) => (
            <tr key={s.id}>
              <td>
                <Button variant="secondary" size="sm" className="py-0 px-2" onClick={() => setViewId(s.id)}>
                  {s.id}
                </Button>
              </td>
              <td><Badge bg="secondary">{s.channelType}</Badge></td>
              <td>{s.messageCount}</td>
              <td><Badge bg={s.state === 'ACTIVE' ? 'success' : 'warning'}>{s.state}</Badge></td>
              <td className="small">{s.updatedAt ? new Date(s.updatedAt).toLocaleString() : '-'}</td>
              <td>
                <div className="d-flex gap-1">
                  <Button
                    size="sm"
                    variant="primary"
                    onClick={async () => {
                      const r = await compactMut.mutateAsync({ id: s.id });
                      toast.success(`Removed ${r.removed} messages`);
                    }}
                  >
                    Compact
                  </Button>
                  <Button
                    size="sm"
                    variant="warning"
                    onClick={() => setConfirmAction({ type: 'clear', sessionId: s.id })}
                    disabled={clearMut.isPending || deleteMut.isPending}
                  >
                    Clear
                  </Button>
                  <Button
                    size="sm"
                    variant="danger"
                    onClick={() => setConfirmAction({ type: 'delete', sessionId: s.id })}
                    disabled={clearMut.isPending || deleteMut.isPending}
                  >
                    Delete
                  </Button>
                </div>
              </td>
            </tr>
          ))}
        </tbody>
      </Table>

      <Modal show={!!viewId} onHide={() => setViewId(null)} size="lg">
        <Modal.Header closeButton>
          <Modal.Title>Session: {viewId}</Modal.Title>
        </Modal.Header>
        <Modal.Body className="sessions-modal-body">
          {detail?.messages.map((msg, i) => (
            <div key={i} className={`mb-2 p-2 rounded ${msg.role === 'user' ? 'bg-primary-subtle text-primary-emphasis' : 'bg-body-tertiary'}`}>
              <div className="fw-bold small">{msg.role}</div>
              <div className="sessions-message">{msg.content}</div>
              {msg.timestamp && <div className="sessions-message-meta">{msg.timestamp}</div>}
            </div>
          ))}
        </Modal.Body>
      </Modal>

      <ConfirmModal
        show={!!confirmAction}
        title={confirmAction?.type === 'clear' ? 'Clear Session' : 'Delete Session'}
        message={
          confirmAction?.type === 'clear'
            ? 'This will remove all messages from the selected session. This action cannot be undone.'
            : 'This will permanently delete the selected session. This action cannot be undone.'
        }
        confirmLabel={confirmAction?.type === 'clear' ? 'Clear' : 'Delete'}
        confirmVariant={confirmAction?.type === 'clear' ? 'warning' : 'danger'}
        isProcessing={clearMut.isPending || deleteMut.isPending}
        onConfirm={handleConfirmAction}
        onCancel={() => setConfirmAction(null)}
      />
    </div>
  );
}
