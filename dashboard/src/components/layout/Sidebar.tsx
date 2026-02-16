import { NavLink } from 'react-router-dom';
import { Nav } from 'react-bootstrap';
import { FiMessageSquare, FiBarChart2, FiSettings, FiFileText, FiZap, FiList, FiActivity } from 'react-icons/fi';

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
  return (
    <div className="sidebar d-flex flex-column">
      <div className="sidebar-brand d-flex align-items-center">
        <span style={{ fontSize: '1.5rem', lineHeight: 1 }}>&#x1F916;</span>
        <span>GolemCore</span>
      </div>
      <Nav className="flex-column flex-grow-1 px-2 py-2">
        {links.map((link) => (
          <Nav.Link
            key={link.to}
            as={NavLink}
            to={link.to}
            end={link.to === '/'}
          >
            {link.icon}
            <span>{link.label}</span>
          </Nav.Link>
        ))}
      </Nav>
      <div className="px-4 py-3 sidebar-footer-text small text-body-secondary">
        v0.1.0
      </div>
    </div>
  );
}
