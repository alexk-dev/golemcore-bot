import type { ReactElement } from 'react';
import { FiChevronDown, FiChevronUp, FiLayout, FiMessageSquare, FiPlus } from 'react-icons/fi';

interface ChatToolbarProps {
  chatSessionId: string;
  connected: boolean;
  panelOpen: boolean;
  collapsed: boolean;
  onNewChat: () => void;
  onToggleContext: () => void;
  onToggleCollapsed: () => void;
}

export function ChatToolbar({
  chatSessionId,
  connected,
  panelOpen,
  collapsed,
  onNewChat,
  onToggleContext,
  onToggleCollapsed,
}: ChatToolbarProps): ReactElement {
  const collapseLabel = collapsed ? 'Expand chat window' : 'Collapse chat window';
  const CollapseIcon = collapsed ? FiChevronDown : FiChevronUp;
  return (
    <div className={`chat-toolbar${collapsed ? ' chat-toolbar--collapsed' : ''}`}>
      <div className="chat-toolbar-inner">
        <div className="chat-toolbar-main">
          <div className="chat-toolbar-title-group">
            <div className="chat-toolbar-title">
              <FiMessageSquare aria-hidden="true" />
              <span>Workspace Chat</span>
            </div>
            <small className="chat-toolbar-subtitle">
              Session: <span className="font-mono">{chatSessionId.slice(0, 8)}</span>
            </small>
          </div>
          <div className="chat-toolbar-status" aria-live="polite">
            <span className={`status-dot ${connected ? 'online' : 'offline'}`} aria-hidden="true" />
            <small className="text-body-secondary">{connected ? 'Connected' : 'Reconnecting...'}</small>
          </div>
        </div>
        <div className="chat-toolbar-actions">
          <button
            type="button"
            className="btn btn-sm btn-secondary chat-toolbar-btn"
            onClick={onNewChat}
            title="Start a new chat session"
            aria-label="Start a new chat session"
          >
            <span className="chat-toolbar-btn-icon" aria-hidden="true">
              <FiPlus size={14} />
            </span>
            <span>New chat</span>
          </button>
          <button
            type="button"
            className="btn btn-sm btn-secondary chat-toolbar-btn panel-toggle-btn"
            onClick={onToggleContext}
            title={panelOpen ? 'Hide context panel' : 'Show context panel'}
            aria-label={panelOpen ? 'Hide context panel' : 'Show context panel'}
          >
            <span className="chat-toolbar-btn-icon" aria-hidden="true">
              <FiLayout size={14} />
            </span>
            <span>{panelOpen ? 'Hide context' : 'Show context'}</span>
          </button>
          <button
            type="button"
            className="btn btn-sm btn-secondary chat-toolbar-btn chat-toolbar-btn--collapse"
            onClick={onToggleCollapsed}
            title={collapseLabel}
            aria-label={collapseLabel}
            aria-expanded={!collapsed}
            aria-controls="chat-workspace-body"
          >
            <span className="chat-toolbar-btn-icon" aria-hidden="true">
              <CollapseIcon size={14} />
            </span>
            <span>{collapsed ? 'Expand' : 'Collapse'}</span>
          </button>
        </div>
      </div>
    </div>
  );
}
