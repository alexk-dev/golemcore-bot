import { useRef, type KeyboardEvent, type ReactElement } from 'react';
import { FiPlus, FiX } from 'react-icons/fi';
import { useChatSessionStore } from '../../store/chatSessionStore';

function formatLabel(index: number): string {
  return `Chat ${index + 1}`;
}

function resolveTargetIndex(
  key: string,
  currentIndex: number,
  total: number,
): number | null {
  if (key === 'ArrowRight') {
    return (currentIndex + 1) % total;
  }
  if (key === 'ArrowLeft') {
    return (currentIndex - 1 + total) % total;
  }
  if (key === 'Home') {
    return 0;
  }
  if (key === 'End') {
    return total - 1;
  }
  return null;
}

/**
 * Renders the workspace chat session strip with keyboard navigation between open
 * sessions.
 */
export function ChatSessionTabs(): ReactElement {
  const openSessionIds = useChatSessionStore((state) => state.openSessionIds);
  const activeSessionId = useChatSessionStore((state) => state.activeSessionId);
  const openSession = useChatSessionStore((state) => state.openSession);
  const closeSession = useChatSessionStore((state) => state.closeSession);
  const startNewSession = useChatSessionStore((state) => state.startNewSession);
  const tabRefs = useRef<Array<HTMLButtonElement | null>>([]);

  const handleSelect = (sessionId: string): void => {
    if (sessionId === activeSessionId) {
      return;
    }
    openSession(sessionId);
  };

  const handleClose = (sessionId: string): void => {
    closeSession(sessionId);
  };

  const handleNew = (): void => {
    startNewSession();
  };

  const handleKeyDown = (event: KeyboardEvent<HTMLButtonElement>, index: number): void => {
    const targetIndex = resolveTargetIndex(event.key, index, openSessionIds.length);
    if (targetIndex == null) {
      return;
    }
    event.preventDefault();
    const targetSessionId = openSessionIds[targetIndex];
    if (targetSessionId == null) {
      return;
    }
    openSession(targetSessionId);
    tabRefs.current[targetIndex]?.focus();
  };

  return (
    <div className="chat-session-tabs" role="tablist" aria-label="Chat sessions">
      {openSessionIds.map((sessionId, index) => {
        const isActive = sessionId === activeSessionId;
        return (
          <div
            key={sessionId}
            className={`chat-session-tab-wrapper ${isActive ? 'chat-session-tab-wrapper-active' : ''}`}
          >
            <button
              type="button"
              role="tab"
              aria-selected={isActive}
              tabIndex={isActive ? 0 : -1}
              data-testid="chat-session-tab"
              data-active={isActive}
              className="chat-session-tab"
              ref={(node) => {
                tabRefs.current[index] = node;
              }}
              onClick={() => handleSelect(sessionId)}
              onKeyDown={(event) => handleKeyDown(event, index)}
              title={`Session ${sessionId.slice(0, 8)}`}
            >
              {formatLabel(index)}
            </button>
            <button
              type="button"
              aria-label="Close session"
              data-testid="chat-session-close"
              className="chat-session-tab-close"
              onClick={() => handleClose(sessionId)}
            >
              <FiX size={12} />
            </button>
          </div>
        );
      })}
      <button
        type="button"
        data-testid="chat-session-new"
        className="chat-session-tab-new"
        onClick={handleNew}
        title="Start a new chat session"
        aria-label="Start a new chat session"
      >
        <FiPlus size={14} />
      </button>
    </div>
  );
}
