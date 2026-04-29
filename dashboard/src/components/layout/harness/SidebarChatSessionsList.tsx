import { FiPlus } from 'react-icons/fi';
import type { SessionSummary } from '../../../api/sessions';

interface SidebarChatSessionsListProps {
  recentSessions: SessionSummary[];
  isLoading: boolean;
  isError: boolean;
  effectiveActiveSessionId: string;
  isCreating: boolean;
  onNewSession: () => void;
  onSessionClick: (conversationKey: string) => void;
}

function getSessionTitle(session: SessionSummary): string {
  if (session.title != null && session.title.length > 0) {
    return session.title;
  }
  if (session.conversationKey.length > 10) {
    return `Session ${session.conversationKey.slice(0, 10)}`;
  }
  return `Session ${session.conversationKey}`;
}

export default function SidebarChatSessionsList({
  recentSessions,
  isLoading,
  isError,
  effectiveActiveSessionId,
  isCreating,
  onNewSession,
  onSessionClick,
}: SidebarChatSessionsListProps) {
  return (
    <div className="harness-sidebar__group">
      <div className="harness-sidebar__group-header">
        <span>Chat sessions</span>
        <button
          type="button"
          className="harness-sidebar__group-action"
          onClick={onNewSession}
          disabled={isCreating}
          aria-label={isCreating ? 'Creating new chat session' : 'New chat session'}
          title="New chat session"
        >
          <FiPlus size={14} />
        </button>
      </div>
      <div className="harness-sidebar__chat-list" role="list" aria-label="Recent chat sessions">
        {isLoading && (
          <div className="harness-sidebar__state" role="status" aria-live="polite">Loading recent sessions…</div>
        )}
        {!isLoading && isError && (
          <div className="harness-sidebar__state harness-sidebar__state--error" role="status" aria-live="polite">Failed to load sessions</div>
        )}
        {!isLoading && !isError && recentSessions.length === 0 && (
          <div className="harness-sidebar__state" role="status" aria-live="polite">No recent sessions</div>
        )}
        {!isLoading && !isError && recentSessions.map((session) => {
          const sessionKey = session.conversationKey;
          const isActive = sessionKey === effectiveActiveSessionId;
          return (
            <button
              key={session.id}
              type="button"
              className={`harness-sidebar__chat-item${isActive ? ' active' : ''}`}
              onClick={() => onSessionClick(sessionKey)}
              role="listitem"
              aria-pressed={isActive}
              title={session.preview ?? getSessionTitle(session)}
            >
              <span className="harness-sidebar__chat-item-title">{getSessionTitle(session)}</span>
              {session.preview != null && session.preview.length > 0 && (
                <span className="harness-sidebar__chat-item-preview">{session.preview}</span>
              )}
            </button>
          );
        })}
      </div>
    </div>
  );
}
