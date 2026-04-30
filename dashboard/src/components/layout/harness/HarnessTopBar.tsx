import { useNavigate } from 'react-router-dom';
import {
  FiArrowUpCircle,
  FiChevronDown,
  FiLogOut,
  FiMenu,
  FiMoon,
  FiSearch,
  FiSidebar,
  FiSun,
  FiPlus,
  FiSettings,
} from 'react-icons/fi';
import { useAuthStore } from '../../../store/authStore';
import { useChatRuntimeStore } from '../../../store/chatRuntimeStore';
import { useChatSessionStore } from '../../../store/chatSessionStore';
import { useThemeStore } from '../../../store/themeStore';
import { useSidebarStore } from '../../../store/sidebarStore';
import { useInspectorStore } from '../../../store/inspectorStore';
import { useCommandPaletteStore } from '../../../store/commandPaletteStore';
import { useBackgroundSystemUpdateCheck } from '../../../hooks/useBackgroundSystemUpdateCheck';
import { useSystemUpdateStatus } from '../../../hooks/useSystem';
import { logout } from '../../../api/auth';
import { getTopbarUpdateNotice } from '../../../utils/systemUpdateUi';
import { createUuid } from '../../../utils/uuid';
import { useCreateSession } from '../../../hooks/useSessions';
import { resolveConnectionStatus, resolveModeLabel, resolvePlanModeLabel, type ConnectionStatus } from './topbarStatus';

interface ConnectionPillProps {
  status: ConnectionStatus;
  sessionId: string | null;
}

function ConnectionPill({ status, sessionId }: ConnectionPillProps) {
  const labelByStatus: Record<ConnectionStatus, string> = {
    connected: 'Connected',
    reconnecting: 'Reconnecting',
    disconnected: 'Disconnected',
  };
  return (
    <span
      className={`harness-topbar__pill harness-topbar__pill--${status}`}
      role="status"
      aria-live="polite"
      title={sessionId != null ? `Session ${sessionId.slice(0, 8)}` : labelByStatus[status]}
    >
      <span className="harness-topbar__pill-dot" />
      <span>{labelByStatus[status]}</span>
    </span>
  );
}

export default function HarnessTopBar() {
  const nav = useNavigate();
  const doLogout = useAuthStore((s) => s.logout);
  const activeSessionId = useChatSessionStore((s) => s.activeSessionId);
  const setActiveSessionId = useChatSessionStore((s) => s.setActiveSessionId);
  const clientInstanceId = useChatSessionStore((s) => s.clientInstanceId);
  const theme = useThemeStore((s) => s.theme);
  const toggleTheme = useThemeStore((s) => s.toggle);
  const toggleMobile = useSidebarStore((s) => s.toggleMobile);
  const mobileOpen = useSidebarStore((s) => s.mobileOpen);
  const inspectorOpen = useInspectorStore((s) => s.panelOpen);
  const togglePanel = useInspectorStore((s) => s.togglePanel);
  const openPalette = useCommandPaletteStore((s) => s.openPalette);
  const connectionState = useChatRuntimeStore((s) => s.connectionState);
  const activeSession = useChatRuntimeStore((s) => s.sessions[activeSessionId]);
  const { data: updateStatus } = useSystemUpdateStatus();
  const createSessionMutation = useCreateSession();

  const status = resolveConnectionStatus(connectionState, activeSession);
  const modeLabel = resolveModeLabel(activeSession);
  const planLabel = resolvePlanModeLabel(activeSession);
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

  const handleNewChat = () => {
    const nextKey = createUuid();
    setActiveSessionId(nextKey);
    nav('/');
    createSessionMutation.mutate({
      channelType: 'web',
      clientInstanceId,
      conversationKey: nextKey,
      activate: true,
    });
  };

  return (
    <header className="harness-topbar" role="banner">
      <div className="harness-topbar__left">
        <button
          type="button"
          className="harness-topbar__icon-btn harness-topbar__mobile-toggle"
          onClick={toggleMobile}
          aria-label={mobileOpen ? 'Close navigation' : 'Open navigation'}
          aria-controls="primary-navigation"
          aria-expanded={mobileOpen}
        >
          <FiMenu size={16} aria-hidden="true" />
        </button>
        <span className="harness-topbar__brand">GolemCore</span>
        <button type="button" className="harness-topbar__menu-btn" aria-label="Switch workspace">
          <span>Workspace Chat</span>
          <FiChevronDown size={14} aria-hidden="true" />
        </button>
      </div>
      <div className="harness-topbar__center">
        <ConnectionPill status={status} sessionId={activeSessionId} />
        <span className="harness-topbar__pill harness-topbar__pill--coding" title="Active model tier">
          <span>{modeLabel}</span>
          <FiChevronDown size={12} aria-hidden="true" />
        </span>
        <span
          className={`harness-topbar__pill harness-topbar__pill--${planLabel === 'Plan ON' ? 'plan-on' : 'plan-off'}`}
          title={`Plan mode is ${planLabel === 'Plan ON' ? 'on' : 'off'}`}
        >
          {planLabel}
        </span>
      </div>
      <div className="harness-topbar__right">
        <button type="button" className="harness-topbar__btn harness-topbar__btn--primary" onClick={handleNewChat}>
          <FiPlus size={14} aria-hidden="true" />
          <span>New chat</span>
        </button>
        <button
          type="button"
          className="harness-topbar__btn"
          aria-label="Open command palette"
          title="Cmd/Ctrl + K"
          onClick={openPalette}
        >
          <FiSearch size={14} aria-hidden="true" />
          <span className="kbd">⌘K</span>
        </button>
        {updateNotice != null && (
          <button
            type="button"
            className="harness-topbar__icon-btn"
            onClick={() => nav('/settings/updates')}
            aria-label={`${updateNotice.title}. Open settings updates.`}
            title={updateNotice.title}
          >
            <FiArrowUpCircle size={16} aria-hidden="true" />
          </button>
        )}
        <button
          type="button"
          className="harness-topbar__icon-btn"
          onClick={togglePanel}
          aria-label={inspectorOpen ? 'Hide inspector' : 'Show inspector'}
          aria-pressed={inspectorOpen}
          title={inspectorOpen ? 'Hide inspector' : 'Show inspector'}
        >
          <FiSidebar size={16} aria-hidden="true" />
        </button>
        <button
          type="button"
          className="harness-topbar__icon-btn"
          onClick={toggleTheme}
          aria-label={`Switch to ${theme === 'light' ? 'dark' : 'light'} mode`}
          title={`Switch to ${theme === 'light' ? 'dark' : 'light'} mode`}
        >
          {theme === 'light' ? <FiMoon size={16} aria-hidden="true" /> : <FiSun size={16} aria-hidden="true" />}
        </button>
        <button
          type="button"
          className="harness-topbar__icon-btn"
          onClick={() => nav('/settings')}
          aria-label="Open settings"
          title="Settings"
        >
          <FiSettings size={16} aria-hidden="true" />
        </button>
        <button
          type="button"
          className="harness-topbar__icon-btn"
          onClick={handleLogout}
          aria-label="Log out"
          title="Log out"
        >
          <FiLogOut size={16} aria-hidden="true" />
        </button>
      </div>
    </header>
  );
}
