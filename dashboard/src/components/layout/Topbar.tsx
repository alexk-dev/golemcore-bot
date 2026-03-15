import { useNavigate } from 'react-router-dom';
import { useAuthStore } from '../../store/authStore';
import { useChatRuntimeStore } from '../../store/chatRuntimeStore';
import { useChatSessionStore } from '../../store/chatSessionStore';
import { useThemeStore } from '../../store/themeStore';
import { useSidebarStore } from '../../store/sidebarStore';
import { useBackgroundSystemUpdateCheck } from '../../hooks/useBackgroundSystemUpdateCheck';
import { useSystemUpdateStatus } from '../../hooks/useSystem';
import { logout } from '../../api/auth';
import { type TopbarUpdateNotice, getTopbarUpdateNotice } from '../../utils/systemUpdateUi';
import { FiAlertCircle, FiArrowUpCircle, FiLogOut, FiMenu, FiMoon, FiRefreshCw, FiSun } from 'react-icons/fi';

interface ChatStatusState {
  trackedSessionId: string | null;
  statusCopy: string | null;
}

function resolveRunningSessionId(sessions: ReturnType<typeof useChatRuntimeStore.getState>['sessions']): string | null {
  for (const [sessionId, session] of Object.entries(sessions)) {
    if (session.running || session.typing) {
      return sessionId;
    }
  }
  return null;
}

function resolveChatStatus(
  activeSessionId: string,
  activeSession: ReturnType<typeof useChatRuntimeStore.getState>['sessions'][string] | undefined,
  runningSessionId: string | null,
  connectionState: ReturnType<typeof useChatRuntimeStore.getState>['connectionState'],
): ChatStatusState {
  const trackedSessionId = activeSession?.running || activeSession?.typing ? activeSessionId : runningSessionId;
  if (trackedSessionId == null) {
    return {
      trackedSessionId: null,
      statusCopy: null,
    };
  }

  if (connectionState === 'reconnecting') {
    return {
      trackedSessionId,
      statusCopy: 'Reconnecting live chat',
    };
  }

  return {
    trackedSessionId,
    statusCopy: activeSession?.typing || (trackedSessionId === activeSessionId && activeSession?.running)
      ? 'Thinking'
      : 'Chat active',
  };
}

function renderUpdateIcon(notice: TopbarUpdateNotice) {
  if (notice.busy) {
    return <FiRefreshCw size={17} aria-hidden="true" />;
  }
  if (notice.tone === 'danger') {
    return <FiAlertCircle size={17} aria-hidden="true" />;
  }
  return <FiArrowUpCircle size={17} aria-hidden="true" />;
}

function TopbarUpdateShortcut({ notice, onClick }: { notice: TopbarUpdateNotice; onClick: () => void }) {
  return (
    <button
      type="button"
      className={`topbar-update-btn topbar-update-btn--${notice.tone}${notice.emphasis ? ' topbar-update-btn--emphasis' : ''}`}
      onClick={onClick}
      title={notice.title}
      aria-label={`${notice.title}. Open Settings updates.`}
    >
      <span className={`topbar-update-icon${notice.busy ? ' topbar-update-icon--busy' : ''}`}>
        {renderUpdateIcon(notice)}
      </span>
      <span className="topbar-update-label">Updates</span>
      <span className={`topbar-update-badge topbar-update-badge--${notice.tone}`} aria-hidden="true">
        {notice.badge}
      </span>
      <span
        className={`topbar-update-dot topbar-update-dot--${notice.tone}${notice.emphasis ? ' topbar-update-dot--emphasis' : ''}`}
        aria-hidden="true"
      />
    </button>
  );
}

export default function Topbar() {
  const nav = useNavigate();
  const doLogout = useAuthStore((s) => s.logout);
  const activeSessionId = useChatSessionStore((s) => s.activeSessionId);
  const setActiveSessionId = useChatSessionStore((s) => s.setActiveSessionId);
  const theme = useThemeStore((s) => s.theme);
  const toggleTheme = useThemeStore((s) => s.toggle);
  const toggleMobile = useSidebarStore((s) => s.toggleMobile);
  const mobileOpen = useSidebarStore((s) => s.mobileOpen);
  const connectionState = useChatRuntimeStore((s) => s.connectionState);
  const activeSession = useChatRuntimeStore((s) => s.sessions[activeSessionId]);
  const runningSessionId = useChatRuntimeStore((s) => resolveRunningSessionId(s.sessions));
  const { data: updateStatus } = useSystemUpdateStatus();
  const chatStatus = resolveChatStatus(activeSessionId, activeSession, runningSessionId, connectionState);
  const updateNotice = getTopbarUpdateNotice(updateStatus);

  useBackgroundSystemUpdateCheck(updateStatus);

  const handleLogout = async () => {
    try {
      await logout();
    } finally {
      doLogout();
      nav('/login');
    }
  };

  const handleOpenChat = () => {
    if (chatStatus.trackedSessionId != null) {
      setActiveSessionId(chatStatus.trackedSessionId);
    }
    nav('/');
  };

  const handleOpenUpdates = () => {
    nav('/settings/updates');
  };

  return (
    <header className="topbar d-flex align-items-center justify-content-between px-3 px-md-4 py-2">
      <button
        type="button"
        className="topbar-icon-btn topbar-mobile-menu-btn d-md-none d-flex align-items-center justify-content-center p-0"
        onClick={toggleMobile}
        aria-label={mobileOpen ? 'Close navigation' : 'Open navigation'}
        aria-controls="primary-navigation"
        aria-expanded={mobileOpen}
      >
        <FiMenu className="topbar-mobile-menu-icon" aria-hidden="true" />
      </button>
      <div className="topbar-center-slot">
        {chatStatus.statusCopy != null && (
          <button
            type="button"
            className="topbar-live-pill"
            onClick={handleOpenChat}
            title={chatStatus.trackedSessionId != null ? `Open chat session ${chatStatus.trackedSessionId.slice(0, 8)}` : 'Open chat'}
          >
            <span className={`topbar-live-dot ${connectionState === 'reconnecting' ? 'topbar-live-dot--reconnecting' : ''}`} />
            <span className="topbar-live-label">{chatStatus.statusCopy}</span>
            {chatStatus.trackedSessionId != null && (
              <span className="topbar-live-session">{chatStatus.trackedSessionId.slice(0, 8)}</span>
            )}
          </button>
        )}
      </div>
      <div className="d-flex align-items-center gap-2">
        {updateNotice != null && (
          <TopbarUpdateShortcut notice={updateNotice} onClick={handleOpenUpdates} />
        )}
        <button
          type="button"
          className="topbar-icon-btn text-decoration-none d-flex align-items-center justify-content-center p-0"
          onClick={toggleTheme}
          title={`Switch to ${theme === 'light' ? 'dark' : 'light'} mode`}
          aria-label={`Switch to ${theme === 'light' ? 'dark' : 'light'} mode`}
        >
          {theme === 'light' ? <FiMoon size={18} /> : <FiSun size={18} />}
        </button>
        <button
          type="button"
          className="topbar-action-btn text-decoration-none d-flex align-items-center px-3"
          onClick={handleLogout}
        >
          <FiLogOut size={16} className="me-0 me-md-2" />
          <span className="fw-medium small d-none d-md-inline">Logout</span>
        </button>
      </div>
    </header>
  );
}
