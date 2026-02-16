import { NavLink } from 'react-router-dom';
import { Nav } from 'react-bootstrap';
import { FiMessageSquare, FiBarChart2, FiSettings, FiFileText, FiZap, FiList } from 'react-icons/fi';

const links = [
  { to: '/', icon: <FiMessageSquare size={18} />, label: 'Chat' },
  { to: '/sessions', icon: <FiList size={18} />, label: 'Sessions' },
  { to: '/analytics', icon: <FiBarChart2 size={18} />, label: 'Analytics' },
  { to: '/prompts', icon: <FiFileText size={18} />, label: 'Prompts' },
  { to: '/skills', icon: <FiZap size={18} />, label: 'Skills' },
  { to: '/settings', icon: <FiSettings size={18} />, label: 'Settings' },
];

export default function Sidebar() {
  return (
    <div className="sidebar d-flex flex-column" style={{ width: 220 }}>
      <div className="px-3 py-3 mb-2">
        <div className="sidebar-brand text-white d-flex align-items-center gap-2">
          <span style={{ fontSize: '1.4rem' }}>&#x1F916;</span>
          <span>GolemCore</span>
        </div>
      </div>
      <Nav className="flex-column flex-grow-1 px-2">
        {links.map((link) => (
          <Nav.Link
            key={link.to}
            as={NavLink}
            to={link.to}
            end={link.to === '/'}
            className="d-flex align-items-center gap-2"
          >
            {link.icon}
            <span>{link.label}</span>
          </Nav.Link>
        ))}
      </Nav>
      <div className="px-3 py-3 text-white-50" style={{ fontSize: '0.75rem' }}>
        v0.1.0
      </div>
    </div>
  );
}
