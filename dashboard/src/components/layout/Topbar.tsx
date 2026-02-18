import { Button } from 'react-bootstrap';
import { useNavigate } from 'react-router-dom';
import { useAuthStore } from '../../store/authStore';
import { useThemeStore } from '../../store/themeStore';
import { useSidebarStore } from '../../store/sidebarStore';
import { logout } from '../../api/auth';
import { FiLogOut, FiSun, FiMoon, FiMenu } from 'react-icons/fi';

export default function Topbar() {
  const nav = useNavigate();
  const doLogout = useAuthStore((s) => s.logout);
  const theme = useThemeStore((s) => s.theme);
  const toggleTheme = useThemeStore((s) => s.toggle);
  const toggleMobile = useSidebarStore((s) => s.toggleMobile);

  const handleLogout = async () => {
    try {
      await logout();
    } finally {
      doLogout();
      nav('/login');
    }
  };

  return (
    <div className="topbar d-flex align-items-center justify-content-between px-3 px-md-4 py-2">
      <Button
        variant="secondary"
        className="topbar-icon-btn d-md-none"
        onClick={toggleMobile}
        aria-label="Toggle navigation"
      >
        <FiMenu size={18} />
      </Button>
      <div className="d-none d-md-block" />
      <div className="d-flex align-items-center gap-2">
        <Button
          variant="secondary"
          className="topbar-icon-btn text-decoration-none d-flex align-items-center justify-content-center p-0"
          onClick={toggleTheme}
          title={`Switch to ${theme === 'light' ? 'dark' : 'light'} mode`}
          aria-label={`Switch to ${theme === 'light' ? 'dark' : 'light'} mode`}
        >
          {theme === 'light' ? <FiMoon size={18} /> : <FiSun size={18} />}
        </Button>
        <Button 
          variant="secondary"
          className="topbar-action-btn text-decoration-none d-flex align-items-center px-3"
          onClick={handleLogout}
        >
          <FiLogOut size={16} className="me-2" />
          <span className="fw-medium small">Logout</span>
        </Button>
      </div>
    </div>
  );
}
