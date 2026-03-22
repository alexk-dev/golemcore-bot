import type { ReactElement } from 'react';
import { Alert, Badge, Card, Placeholder, Spinner } from 'react-bootstrap';
import { Link, Navigate, useParams } from 'react-router-dom';

import { SessionTraceTab } from '../components/sessions/SessionTraceTab';
import { useSession } from '../hooks/useSessions';
import { extractErrorMessage } from '../utils/extractErrorMessage';

type SessionDetailsTab = 'messages' | 'trace';

interface SessionMessagesViewProps {
  messages: Array<{
    id: string;
    role: string;
    content: string;
    timestamp: string | null;
  }>;
}

function formatUpdatedAt(value: string | null): string {
  if (value == null || value.length === 0) {
    return '-';
  }
  return new Date(value).toLocaleString();
}

function resolveActiveTab(tab: string | undefined): SessionDetailsTab | null {
  if (tab == null || tab.length === 0) {
    return null;
  }
  if (tab === 'messages' || tab === 'trace') {
    return tab;
  }
  return null;
}

function SessionMessagesView({ messages }: SessionMessagesViewProps): ReactElement {
  return (
    <div className="d-flex flex-column gap-3">
      {messages.length > 0 ? messages.map((message) => (
        <div
          key={message.id}
          className={`session-trace-bubble ${
            message.role === 'user'
              ? 'session-trace-bubble-user'
              : message.role === 'assistant'
                ? 'session-trace-bubble-assistant'
                : 'session-trace-bubble-tool'
          }`}
        >
          <div className="fw-semibold">{message.role}</div>
          <div className="session-trace-bubble-content mt-2">{message.content}</div>
          {message.timestamp != null && message.timestamp.length > 0 && (
            <div className="session-trace-bubble-meta mt-2">{new Date(message.timestamp).toLocaleString()}</div>
          )}
        </div>
      )) : (
        <Alert variant="secondary" className="mb-0">
          No messages stored for this session.
        </Alert>
      )}
    </div>
  );
}

export default function SessionDetailsPage(): ReactElement {
  const { sessionId, tab } = useParams<{ sessionId: string; tab?: string }>();
  const sessionQuery = useSession(sessionId ?? '');

  if (sessionId == null || sessionId.length === 0) {
    return <Navigate to="/sessions" replace />;
  }

  const activeTab = resolveActiveTab(tab);
  if (activeTab == null) {
    return <Navigate to={`/sessions/${sessionId}/messages`} replace />;
  }

  if (sessionQuery.isLoading) {
    return (
      <div className="d-flex flex-column gap-3">
        <div className="section-header d-flex align-items-center justify-content-between">
          <h4 className="mb-0">Session {sessionId}</h4>
          <Link to="/sessions" className="btn btn-sm btn-secondary">
            Back to sessions
          </Link>
        </div>
        <Card>
          <Card.Body>
            <Placeholder as="div" animation="glow" className="mb-2">
              <Placeholder xs={12} />
            </Placeholder>
            <Placeholder as="div" animation="glow" className="mb-2">
              <Placeholder xs={8} />
            </Placeholder>
            <div className="d-flex justify-content-center pt-2">
              <Spinner size="sm" />
            </div>
          </Card.Body>
        </Card>
      </div>
    );
  }

  if (sessionQuery.isError || sessionQuery.data == null) {
    return (
      <div className="d-flex flex-column gap-3">
        <div className="section-header d-flex align-items-center justify-content-between">
          <h4 className="mb-0">Session {sessionId}</h4>
          <Link to="/sessions" className="btn btn-sm btn-secondary">
            Back to sessions
          </Link>
        </div>
        <Alert variant="danger" className="mb-0">
          Failed to load session: {extractErrorMessage(sessionQuery.error)}
        </Alert>
      </div>
    );
  }

  const session = sessionQuery.data;

  return (
    <div className="d-flex flex-column gap-3">
      <div className="section-header d-flex flex-wrap align-items-center justify-content-between gap-2">
        <div>
          <h4 className="mb-1">Session {session.id}</h4>
          <div className="small text-body-secondary">Updated {formatUpdatedAt(session.updatedAt)}</div>
        </div>
        <Link to="/sessions" className="btn btn-sm btn-secondary">
          Back to sessions
        </Link>
      </div>

      <Card className="settings-card">
        <Card.Body className="d-flex flex-wrap gap-2">
          <Badge bg="secondary">{session.channelType}</Badge>
          <Badge bg={session.state === 'ACTIVE' ? 'success' : 'warning'}>{session.state}</Badge>
          <Badge bg="secondary">{session.chatId}</Badge>
          <Badge bg="secondary">{session.conversationKey}</Badge>
        </Card.Body>
      </Card>

      <div className="d-flex flex-wrap gap-2">
        <Link
          to={`/sessions/${session.id}/messages`}
          className={`btn btn-sm ${activeTab === 'messages' ? 'btn-primary' : 'btn-secondary'}`}
        >
          Messages
        </Link>
        <Link
          to={`/sessions/${session.id}/trace`}
          className={`btn btn-sm ${activeTab === 'trace' ? 'btn-primary' : 'btn-secondary'}`}
        >
          Trace
        </Link>
      </div>

      {activeTab === 'messages' ? (
        <SessionMessagesView messages={session.messages} />
      ) : (
        <SessionTraceTab sessionId={session.id} messages={session.messages} />
      )}
    </div>
  );
}
