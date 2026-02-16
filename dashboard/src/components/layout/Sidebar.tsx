import { NavLink } from 'react-router-dom';
import { Nav } from 'react-bootstrap';
import { FiHome, FiMessageSquare, FiBarChart2, FiSettings, FiFileText, FiZap, FiList } from 'react-icons/fi';

const links = [
  { to: '/', icon: <FiHome />, label: 'Dashboard' },
  { to: '/chat', icon: <FiMessageSquare />, label: 'Chat' },
  { to: '/analytics', icon: <FiBarChart2 />, label: 'Analytics' },
  { to: '/prompts', icon: <FiFileText />, label: 'Prompts' },
  { to: '/skills', icon: <FiZap />, label: 'Skills' },
  { to: '/sessions', icon: <FiList />, label: 'Sessions' },
  { to: '/settings', icon: <FiSettings />, label: 'Settings' },
];

export default function Sidebar() {
  return (
    <div className="sidebar d-flex flex-column p-3" style={{ width: 240 }}>
      <h5 className="text-white mb-4 px-2">GolemCore</h5>
      <Nav className="flex-column">
        {links.map((link) => (
          <Nav.Link
            key={link.to}
            as={NavLink}
            to={link.to}
            end={link.to === '/'}
            className="d-flex align-items-center gap-2"
          >
            {link.icon}
            {link.label}
          </Nav.Link>
        ))}
      </Nav>
    </div>
  );
}
