import { NavLink, useNavigate } from 'react-router-dom';
import { Badge, Nav } from 'react-bootstrap';
import { FiMessageSquare, FiBarChart2, FiSettings, FiFileText, FiZap, FiList, FiActivity, FiTerminal, FiX } from 'react-icons/fi';
import type { SessionSummary } from '../../api/sessions';
import { useSidebarStore } from '../../store/sidebarStore';
import { useChatSessionStore } from '../../store/chatSessionStore';
import { useActiveSession, useCreateSession, useRecentSessions } from '../../hooks/useSessions';
import { useSystemHealth, useSystemUpdateStatus } from '../../hooks/useSystem';
import { createUuid } from '../../utils/uuid';
import { isLegacyCompatibleConversationKey, normalizeConversationKey } from '../../utils/conversationKey';
import { getSidebarUpdateBadge } from '../../utils/systemUpdateUi';

const RECENT_SESSIONS_LIMIT = 5;

const links = [
  { to: '/', icon: <FiMessageSquare size={20} />, label: 'Chat' },
  { to: '/sessions', icon: <FiList size={20} />, label: 'Sessions' },
  { to: '/analytics', icon: <FiBarChart2 size={20} />, label: 'Analytics' },
  { to: '/prompts', icon: <FiFileText size={20} />, label: 'Prompts' },
  { to: '/skills', icon: <FiZap size={20} />, label: 'Skills' },
  { to: '/diagnostics', icon: <FiActivity size={20} />, label: 'Diagnostics' },
  { to: '/logs', icon: <FiTerminal size={20} />, label: 'Logs' },
  { to: '/settings', icon: <FiSettings size={20} />, label: 'Settings' },
];

function getSessionTitle(session: SessionSummary): string {
  if (session.title != null && session.title.length > 0) {
    return session.title;
  }
  if (session.conversationKey.length > 10) {
    return `Session ${session.conversationKey.slice(0, 10)}`;
  }
  return `Session ${session.conversationKey}`;
}

export default function Sidebar() {
  const navigate = useNavigate();
  const mobileOpen = useSidebarStore((s) => s.mobileOpen);
  const closeMobile = useSidebarStore((s) => s.closeMobile);
  const clientInstanceId = useChatSessionStore((s) => s.clientInstanceId);
  const activeSessionId = useChatSessionStore((s) => s.activeSessionId);
  const setActiveSessionId = useChatSessionStore((s) => s.setActiveSessionId);
  const { data: health } = useSystemHealth();
  const { data: updateStatus } = useSystemUpdateStatus();
  const {
    data: recentSessionsData,
    isLoading: recentSessionsLoading,
    isError: recentSessionsError,
  } = useRecentSessions('web', clientInstanceId, RECENT_SESSIONS_LIMIT);
  const { data: activeSessionData } = useActiveSession('web', clientInstanceId);
  const createSessionMutation = useCreateSession();
  const version = health?.version ? `v${health.version}` : 'v...';
  const updateState = updateStatus?.state ?? '';
  const settingsBadge = getSidebarUpdateBadge(updateState);
  const recentSessions = recentSessionsData ?? [];
  const serverConversationKey = normalizeConversationKey(activeSessionData?.conversationKey);
  const effectiveActiveSessionId = serverConversationKey ?? activeSessionId;

  const handleNavClick = () => {
    // Close sidebar on mobile when navigation item is clicked
    closeMobile();
  };

  const handleNewSession = () => {
    const nextConversationKey = createUuid();
    setActiveSessionId(nextConversationKey);
    closeMobile();
    navigate('/');
    createSessionMutation.mutate({
      channelType: 'web',
      clientInstanceId,
      conversationKey: nextConversationKey,
      activate: true,
    });
  };

  const handleSessionClick = (conversationKey: string) => {
    const normalized = normalizeConversationKey(conversationKey);
    if (normalized == null || !isLegacyCompatibleConversationKey(normalized)) {
      return;
    }
    setActiveSessionId(normalized);
    closeMobile();
    navigate('/');
  };

  return (
    <>
      {mobileOpen && (
        <button
          type="button"
          className="sidebar-overlay d-md-none"
          onClick={closeMobile}
          aria-label="Close navigation menu"
        />
      )}
      <aside
        id="primary-navigation"
        className={`sidebar d-flex flex-column ${mobileOpen ? 'mobile-open' : ''}`}
        aria-label="Primary navigation"
      >
        <div className="sidebar-brand d-flex align-items-center justify-content-between">
          <div className="d-flex align-items-center gap-2">
            <span className="brand-icon">&#x1F916;</span>
            <span className="sidebar-brand-text">GolemCore</span>
          </div>
          <button
            type="button"
            className="sidebar-close-btn d-md-none"
            onClick={closeMobile}
            aria-label="Close navigation"
          >
            <FiX size={20} />
          </button>
        </div>
        <Nav className="flex-column flex-grow-1 px-2 py-2">
          {links.map((link) => (
            <Nav.Link
              key={link.to}
              as={NavLink}
              to={link.to}
              end={link.to === '/'}
              onClick={handleNavClick}
            >
              {link.icon}
              <span className="sidebar-link-text">{link.label}</span>
              {link.to === '/settings' && settingsBadge != null && (
                <Badge
                  bg={settingsBadge.variant}
                  text={settingsBadge.text}
                  pill
                  className="ms-auto"
                  title={settingsBadge.title}
                >
                  {settingsBadge.label}
                </Badge>
              )}
            </Nav.Link>
          ))}

          <div className="sidebar-chat-group">
            <div className="sidebar-chat-group-header">
              <span className="sidebar-chat-group-label">Chat Sessions</span>
              <button
                type="button"
                className="sidebar-chat-new-btn"
                onClick={handleNewSession}
                disabled={createSessionMutation.isPending}
              >
                {createSessionMutation.isPending ? 'Creating...' : 'New'}
              </button>
            </div>

            <div className="sidebar-chat-list" role="list" aria-label="Recent chat sessions">
              {recentSessionsLoading && (
                <div className="sidebar-chat-state" role="status" aria-live="polite">
                  Loading recent sessions...
                </div>
              )}

              {!recentSessionsLoading && recentSessionsError && (
                <div className="sidebar-chat-state sidebar-chat-state--error" role="status" aria-live="polite">
                  Failed to load sessions
                </div>
              )}

              {!recentSessionsLoading && !recentSessionsError && recentSessions.length === 0 && (
                <div className="sidebar-chat-state" role="status" aria-live="polite">
                  No recent sessions
                </div>
              )}

              {!recentSessionsLoading && !recentSessionsError && recentSessions.map((session) => {
                const sessionKey = session.conversationKey;
                const isActive = sessionKey === effectiveActiveSessionId;
                return (
                  <button
                    key={session.id}
                    type="button"
                    className={`sidebar-chat-item${isActive ? ' active' : ''}`}
                    onClick={() => handleSessionClick(sessionKey)}
                    title={session.preview ?? getSessionTitle(session)}
                    role="listitem"
                    aria-pressed={isActive}
                  >
                    <span className="sidebar-chat-item-title">{getSessionTitle(session)}</span>
                    {session.preview != null && session.preview.length > 0 && (
                      <span className="sidebar-chat-item-preview">{session.preview}</span>
                    )}
                  </button>
                );
              })}
            </div>
          </div>
        </Nav>
        <div className="px-4 py-3 sidebar-footer-text small text-body-secondary">
          {version}
        </div>
      </aside>
    </>
  );
}
