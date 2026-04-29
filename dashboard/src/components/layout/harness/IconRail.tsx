import { useNavigate, useLocation } from 'react-router-dom';
import {
  FiBarChart2,
  FiCalendar,
  FiCode,
  FiMessageSquare,
  FiSearch,
  FiSettings,
  FiTerminal,
  FiUser,
  FiZap,
} from 'react-icons/fi';
import type { ReactNode } from 'react';

interface RailItem {
  key: string;
  to: string;
  matchPrefixes: string[];
  icon: ReactNode;
  ariaLabel: string;
}

const RAIL_ITEMS: RailItem[] = [
  { key: 'chat', to: '/', matchPrefixes: ['/'], icon: <FiMessageSquare size={18} />, ariaLabel: 'Open chat workspace' },
  { key: 'search', to: '/sessions', matchPrefixes: ['/sessions'], icon: <FiSearch size={18} />, ariaLabel: 'Open sessions search' },
  { key: 'automation', to: '/scheduler', matchPrefixes: ['/scheduler', '/webhooks'], icon: <FiCalendar size={18} />, ariaLabel: 'Open automation' },
  { key: 'workspace', to: '/workspace', matchPrefixes: ['/workspace', '/ide'], icon: <FiCode size={18} />, ariaLabel: 'Open workspace' },
  { key: 'agent', to: '/skills', matchPrefixes: ['/skills', '/prompts'], icon: <FiZap size={18} />, ariaLabel: 'Open agent skills' },
  { key: 'observability', to: '/analytics', matchPrefixes: ['/analytics', '/diagnostics', '/self-evolving'], icon: <FiBarChart2 size={18} />, ariaLabel: 'Open observability' },
  { key: 'logs', to: '/logs', matchPrefixes: ['/logs'], icon: <FiTerminal size={18} />, ariaLabel: 'Open logs' },
  { key: 'settings', to: '/settings', matchPrefixes: ['/settings'], icon: <FiSettings size={18} />, ariaLabel: 'Open settings' },
];

function isRailItemActive(item: RailItem, pathname: string): boolean {
  if (item.matchPrefixes.includes('/')) {
    return pathname === '/' || pathname.startsWith('/chat');
  }
  return item.matchPrefixes.some((prefix) => pathname.startsWith(prefix));
}

interface IconRailProps {
  versionLabel: string;
  onActivityClick?: () => void;
}

function IconRailItem({ item, active, onClick }: { item: RailItem; active: boolean; onClick: () => void }) {
  return (
    <button
      type="button"
      className={`harness-rail__btn${active ? ' harness-rail__btn--active' : ''}`}
      onClick={onClick}
      aria-label={item.ariaLabel}
      title={item.ariaLabel}
    >
      {item.icon}
    </button>
  );
}

function IconRailContent({ versionLabel }: { versionLabel: string }) {
  const navigate = useNavigate();
  const { pathname } = useLocation();

  return (
    <aside className="harness-rail" aria-label="Workspace shortcuts">
      <div className="harness-rail__brand" aria-hidden="true">G</div>
      <div className="harness-rail__items">
        {RAIL_ITEMS.map((item) => (
          <IconRailItem
            key={item.key}
            item={item}
            active={isRailItemActive(item, pathname)}
            onClick={() => navigate(item.to)}
          />
        ))}
      </div>
      <div className="harness-rail__footer">
        <button
          type="button"
          className="harness-rail__btn"
          aria-label="Profile and account"
          title="Profile"
        >
          <FiUser size={16} />
        </button>
        <span className="harness-rail__version" aria-label={`Dashboard version ${versionLabel}`}>{versionLabel}</span>
      </div>
    </aside>
  );
}

export default function IconRail({ versionLabel }: IconRailProps) {
  return <IconRailContent versionLabel={versionLabel} />;
}
