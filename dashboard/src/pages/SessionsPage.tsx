import { useState } from 'react';
import { Table, Button, Badge, Modal, Spinner } from 'react-bootstrap';
import { useSessions, useSession, useDeleteSession, useCompactSession, useClearSession } from '../hooks/useSessions';
import toast from 'react-hot-toast';

export default function SessionsPage() {
  const { data: sessions, isLoading } = useSessions();
  const deleteMut = useDeleteSession();
  const compactMut = useCompactSession();
  const clearMut = useClearSession();
  const [viewId, setViewId] = useState<string | null>(null);
  const { data: detail } = useSession(viewId ?? '');

  if (isLoading) return <Spinner />;

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
                <Button variant="link" size="sm" className="p-0" onClick={() => setViewId(s.id)}>
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
                    onClick={() => { clearMut.mutate(s.id); toast.success('Cleared'); }}
                  >
                    Clear
                  </Button>
                  <Button
                    size="sm"
                    variant="danger"
                    onClick={() => { deleteMut.mutate(s.id); toast.success('Deleted'); }}
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
        <Modal.Body style={{ maxHeight: '60vh', overflowY: 'auto' }}>
          {detail?.messages.map((msg, i) => (
            <div key={i} className={`mb-2 p-2 rounded ${msg.role === 'user' ? 'bg-primary bg-opacity-10' : 'bg-body-secondary'}`}>
              <div className="fw-bold small">{msg.role}</div>
              <div style={{ whiteSpace: 'pre-wrap' }}>{msg.content}</div>
              {msg.timestamp && <div className="text-muted" style={{ fontSize: '0.7rem' }}>{msg.timestamp}</div>}
            </div>
          ))}
        </Modal.Body>
      </Modal>
    </div>
  );
}
