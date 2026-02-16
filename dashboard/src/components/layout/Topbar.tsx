import { Button } from 'react-bootstrap';
import { useNavigate } from 'react-router-dom';
import { useAuthStore } from '../../store/authStore';
import { useThemeStore } from '../../store/themeStore';
import { logout } from '../../api/auth';
import { FiLogOut, FiSun, FiMoon } from 'react-icons/fi';

export default function Topbar() {
  const nav = useNavigate();
  const doLogout = useAuthStore((s) => s.logout);
  const theme = useThemeStore((s) => s.theme);
  const toggleTheme = useThemeStore((s) => s.toggle);

  const handleLogout = async () => {
    try {
      await logout();
    } finally {
      doLogout();
      nav('/login');
    }
  };

  return (
    <div className="topbar d-flex align-items-center justify-content-end px-4 py-2">
      <div className="d-flex align-items-center gap-2">
        <Button
          variant={theme === 'light' ? 'outline-secondary' : 'outline-light'}
          size="sm"
          onClick={toggleTheme}
          title={`Switch to ${theme === 'light' ? 'dark' : 'light'} mode`}
        >
          {theme === 'light' ? <FiMoon size={16} /> : <FiSun size={16} />}
        </Button>
        <Button variant="outline-secondary" size="sm" onClick={handleLogout}>
          <FiLogOut size={14} className="me-1" />
          Logout
        </Button>
      </div>
    </div>
  );
}
