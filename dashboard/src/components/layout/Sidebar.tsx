import { NavLink } from 'react-router-dom';
import { Nav } from 'react-bootstrap';
import { FiMessageSquare, FiBarChart2, FiSettings, FiFileText, FiZap, FiList, FiActivity, FiX } from 'react-icons/fi';
import { useSidebarStore } from '../../store/sidebarStore';
import { useSystemHealth } from '../../hooks/useSystem';

const links = [
  { to: '/', icon: <FiMessageSquare size={20} />, label: 'Chat' },
  { to: '/sessions', icon: <FiList size={20} />, label: 'Sessions' },
  { to: '/analytics', icon: <FiBarChart2 size={20} />, label: 'Analytics' },
  { to: '/prompts', icon: <FiFileText size={20} />, label: 'Prompts' },
  { to: '/skills', icon: <FiZap size={20} />, label: 'Skills' },
  { to: '/diagnostics', icon: <FiActivity size={20} />, label: 'Diagnostics' },
  { to: '/settings', icon: <FiSettings size={20} />, label: 'Settings' },
];

export default function Sidebar() {
  const mobileOpen = useSidebarStore((s) => s.mobileOpen);
  const closeMobile = useSidebarStore((s) => s.closeMobile);
  const { data: health } = useSystemHealth();
  const version = health?.version ? `v${health.version}` : 'v...';

  const handleNavClick = () => {
    // Close sidebar on mobile when navigation item is clicked
    closeMobile();
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
            </Nav.Link>
          ))}
        </Nav>
        <div className="px-4 py-3 sidebar-footer-text small text-body-secondary">
          {version}
        </div>
      </aside>
    </>
  );
}
