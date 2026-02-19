import { type ReactElement, useState } from 'react';
import { Table, Button, Badge, Modal, Spinner, Card, Placeholder } from 'react-bootstrap';
import { useSessions, useSession, useDeleteSession, useCompactSession, useClearSession } from '../hooks/useSessions';
import type { SessionSummary } from '../api/sessions';
import toast from 'react-hot-toast';
import ConfirmModal from '../components/common/ConfirmModal';

interface ConfirmAction {
  type: 'clear' | 'delete';
  sessionId: string;
}

interface SessionRowProps {
  session: SessionSummary;
  actionsDisabled: boolean;
  onOpen: (sessionId: string) => void;
  onCompact: (sessionId: string) => Promise<void>;
  onClear: (sessionId: string) => void;
  onDelete: (sessionId: string) => void;
}

interface SessionsTableProps {
  sessions: SessionSummary[];
  actionsDisabled: boolean;
  onOpen: (sessionId: string) => void;
  onCompact: (sessionId: string) => Promise<void>;
  onClear: (sessionId: string) => void;
  onDelete: (sessionId: string) => void;
}

interface ConfirmCopy {
  title: string;
  message: string;
  confirmLabel: string;
  confirmVariant: 'warning' | 'danger';
}

function formatUpdatedAt(value: string | null): string {
  if (value == null || value.length === 0) {
    return '-';
  }
  return new Date(value).toLocaleString();
}

function getConfirmCopy(actionType: ConfirmAction['type']): ConfirmCopy {
  if (actionType === 'clear') {
    return {
      title: 'Clear Session',
      message: 'This will remove all messages from the selected session. This action cannot be undone.',
      confirmLabel: 'Clear',
      confirmVariant: 'warning',
    };
  }
  return {
    title: 'Delete Session',
    message: 'This will permanently delete the selected session. This action cannot be undone.',
    confirmLabel: 'Delete',
    confirmVariant: 'danger',
  };
}

function SessionRow({
  session,
  actionsDisabled,
  onOpen,
  onCompact,
  onClear,
  onDelete,
}: SessionRowProps): ReactElement {
  return (
    <tr>
      <td data-label="ID">
        <Button type="button"
          variant="secondary"
          size="sm"
          className="py-0 px-2 session-id-btn"
          onClick={() => onOpen(session.id)}
          title={session.id}
        >
          {session.id.length > 8 ? `${session.id.slice(0, 8)}...` : session.id}
        </Button>
      </td>
      <td data-label="Channel"><Badge bg="secondary">{session.channelType}</Badge></td>
      <td data-label="Messages">{session.messageCount}</td>
      <td data-label="State"><Badge bg={session.state === 'ACTIVE' ? 'success' : 'warning'}>{session.state}</Badge></td>
      <td data-label="Updated" className="small">{formatUpdatedAt(session.updatedAt)}</td>
      <td data-label="Actions">
        <div className="d-flex flex-wrap gap-1 sessions-actions">
          <Button type="button"
            size="sm"
            variant="primary"
            className="sessions-action-btn"
            onClick={() => { void onCompact(session.id); }}
          >
            Compact
          </Button>
          <Button type="button"
            size="sm"
            variant="warning"
            className="sessions-action-btn"
            onClick={() => onClear(session.id)}
            disabled={actionsDisabled}
          >
            Clear
          </Button>
          <Button type="button"
            size="sm"
            variant="danger"
            className="sessions-action-btn"
            onClick={() => onDelete(session.id)}
            disabled={actionsDisabled}
          >
            Delete
          </Button>
        </div>
      </td>
    </tr>
  );
}

function SessionsTable({
  sessions,
  actionsDisabled,
  onOpen,
  onCompact,
  onClear,
  onDelete,
}: SessionsTableProps): ReactElement {
  return (
    <Table hover responsive className="dashboard-table responsive-table sessions-table">
      <thead>
        <tr>
          <th scope="col">ID</th>
          <th scope="col">Channel</th>
          <th scope="col">Messages</th>
          <th scope="col">State</th>
          <th scope="col">Updated</th>
          <th scope="col">Actions</th>
        </tr>
      </thead>
      <tbody>
        {sessions.length > 0 ? sessions.map((session) => (
          <SessionRow
            key={session.id}
            session={session}
            actionsDisabled={actionsDisabled}
            onOpen={onOpen}
            onCompact={onCompact}
            onClear={onClear}
            onDelete={onDelete}
          />
        )) : (
          <tr>
            <td colSpan={6} className="text-center text-body-secondary py-4 sessions-empty-cell">
              No sessions found.
            </td>
          </tr>
        )}
      </tbody>
    </Table>
  );
}

export default function SessionsPage(): ReactElement {
  const { data: sessionsData, isLoading } = useSessions();
  const deleteMut = useDeleteSession();
  const compactMut = useCompactSession();
  const clearMut = useClearSession();
  const [viewId, setViewId] = useState<string | null>(null);
  const [confirmAction, setConfirmAction] = useState<ConfirmAction | null>(null);
  const { data: detail } = useSession(viewId ?? '');
  const sessions = sessionsData ?? [];
  const confirmCopy = getConfirmCopy(confirmAction?.type ?? 'delete');

  const handleCompact = async (sessionId: string): Promise<void> => {
    const result = await compactMut.mutateAsync({ id: sessionId });
    toast.success(`Removed ${result.removed} messages`);
  };

  const handleConfirmAction = async (): Promise<void> => {
    if (confirmAction == null) {
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
      <SessionsTable
        sessions={sessions}
        actionsDisabled={clearMut.isPending || deleteMut.isPending}
        onOpen={(sessionId) => setViewId(sessionId)}
        onCompact={handleCompact}
        onClear={(sessionId) => setConfirmAction({ type: 'clear', sessionId })}
        onDelete={(sessionId) => setConfirmAction({ type: 'delete', sessionId })}
      />

      <Modal show={viewId != null && viewId.length > 0} onHide={() => setViewId(null)} size="lg">
        <Modal.Header closeButton>
          <Modal.Title>Session: {viewId}</Modal.Title>
        </Modal.Header>
        <Modal.Body className="sessions-modal-body">
          {detail?.messages.map((msg, i) => (
            <div key={i} className={`mb-2 p-2 rounded ${msg.role === 'user' ? 'bg-primary-subtle text-primary-emphasis' : 'bg-body-tertiary'}`}>
              <div className="fw-bold small">{msg.role}</div>
              <div className="sessions-message">{msg.content}</div>
              {msg.timestamp != null && msg.timestamp.length > 0 && <div className="sessions-message-meta">{msg.timestamp}</div>}
            </div>
          ))}
        </Modal.Body>
      </Modal>

      <ConfirmModal
        show={confirmAction != null}
        title={confirmCopy.title}
        message={confirmCopy.message}
        confirmLabel={confirmCopy.confirmLabel}
        confirmVariant={confirmCopy.confirmVariant}
        isProcessing={clearMut.isPending || deleteMut.isPending}
        onConfirm={() => { void handleConfirmAction(); }}
        onCancel={() => setConfirmAction(null)}
      />
    </div>
  );
}
