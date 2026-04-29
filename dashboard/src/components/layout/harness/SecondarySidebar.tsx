import { NavLink, useNavigate } from 'react-router-dom';
import { FiX } from 'react-icons/fi';
import { useChatSessionStore } from '../../../store/chatSessionStore';
import { useSidebarStore } from '../../../store/sidebarStore';
import { useActiveSession, useCreateSession, useRecentSessions } from '../../../hooks/useSessions';
import { useRuntimeConfig } from '../../../hooks/useSettings';
import { useSystemHealth, useSystemUpdateStatus } from '../../../hooks/useSystem';
import { isLegacyCompatibleConversationKey, normalizeConversationKey } from '../../../utils/conversationKey';
import { getSidebarUpdateBadge } from '../../../utils/systemUpdateUi';
import { createUuid } from '../../../utils/uuid';
import { SIDEBAR_GROUPS, type SidebarLink } from './sidebarNavGroups';
import SidebarChatSessionsList from './SidebarChatSessionsList';

const RECENT_SESSIONS_LIMIT = 5;

function shouldRenderLink(link: SidebarLink, isSelfEvolvingEnabled: boolean): boolean {
  if (link.requiresFlag === 'selfEvolving') {
    return isSelfEvolvingEnabled;
  }
  return true;
}

function SidebarBadge({ link }: { link: SidebarLink }) {
  const { data: updateStatus } = useSystemUpdateStatus();
  if (link.to !== '/settings') {
    return null;
  }
  const badge = getSidebarUpdateBadge(updateStatus?.state ?? '');
  if (badge == null) {
    return null;
  }
  return (
    <span className="harness-sidebar__badge" title={badge.title}>
      {badge.label}
    </span>
  );
}

export default function SecondarySidebar() {
  const navigate = useNavigate();
  const mobileOpen = useSidebarStore((s) => s.mobileOpen);
  const closeMobile = useSidebarStore((s) => s.closeMobile);
  const clientInstanceId = useChatSessionStore((s) => s.clientInstanceId);
  const activeSessionId = useChatSessionStore((s) => s.activeSessionId);
  const setActiveSessionId = useChatSessionStore((s) => s.setActiveSessionId);
  const { data: runtimeConfig } = useRuntimeConfig();
  const { data: health } = useSystemHealth();
  const isSelfEvolvingEnabled = runtimeConfig?.selfEvolving?.enabled === true;
  const versionLabel = health?.version != null ? `v${health.version}` : 'v…';
  const {
    data: recentSessionsData,
    isLoading: recentSessionsLoading,
    isError: recentSessionsError,
  } = useRecentSessions('web', clientInstanceId, RECENT_SESSIONS_LIMIT);
  const { data: activeSessionData } = useActiveSession('web', clientInstanceId);
  const createSessionMutation = useCreateSession();
  const recentSessions = recentSessionsData ?? [];
  const serverConversationKey = normalizeConversationKey(activeSessionData?.conversationKey);
  const effectiveActiveSessionId = serverConversationKey ?? activeSessionId;

  const handleNavClick = () => closeMobile();

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
          className="harness-sidebar-overlay d-md-none"
          onClick={closeMobile}
          aria-label="Close navigation menu"
        />
      )}
      <aside
        id="primary-navigation"
        className={`harness-sidebar sidebar${mobileOpen ? ' mobile-open' : ''}`}
        aria-label="Primary navigation"
      >
        <div className="harness-sidebar__brand">
          <span className="harness-sidebar__brand-mark" aria-hidden="true">G</span>
          <span className="harness-sidebar__brand-text">GolemCore</span>
          <button
            type="button"
            className="harness-sidebar__close-btn d-md-none"
            onClick={closeMobile}
            aria-label="Close navigation"
          >
            <FiX size={16} />
          </button>
        </div>
        <nav className="harness-sidebar__nav" aria-label="Primary sections">
          {SIDEBAR_GROUPS.map((group) => (
            <div key={group.id} className="harness-sidebar__group">
              <div className="harness-sidebar__group-header">
                <span className="harness-sidebar__group-label">{group.label}</span>
              </div>
              {group.links.filter((link) => shouldRenderLink(link, isSelfEvolvingEnabled)).map((link) => (
                <NavLink
                  key={link.to}
                  to={link.to}
                  end={link.end === true}
                  onClick={handleNavClick}
                  aria-label={link.label}
                  title={link.label}
                  className={({ isActive }) => `harness-sidebar__link${isActive ? ' active' : ''}`}
                >
                  <span className="harness-sidebar__link-icon" aria-hidden="true">{link.icon}</span>
                  <span className="harness-sidebar__link-text">{link.label}</span>
                  <SidebarBadge link={link} />
                </NavLink>
              ))}
            </div>
          ))}
          <SidebarChatSessionsList
            recentSessions={recentSessions}
            isLoading={recentSessionsLoading}
            isError={recentSessionsError}
            effectiveActiveSessionId={effectiveActiveSessionId}
            isCreating={createSessionMutation.isPending}
            onNewSession={handleNewSession}
            onSessionClick={handleSessionClick}
          />
        </nav>
        <div className="harness-sidebar__footer" aria-label={`Dashboard version ${versionLabel}`}>
          <span className="harness-sidebar__footer-mark" aria-hidden="true">●</span>
          <span className="harness-sidebar__footer-text">{versionLabel}</span>
        </div>
      </aside>
    </>
  );
}
