import { Button, Navbar, Container } from 'react-bootstrap';
import { useNavigate } from 'react-router-dom';
import { useAuthStore } from '../../store/authStore';
import { logout } from '../../api/auth';
import { FiLogOut } from 'react-icons/fi';

export default function Topbar() {
  const nav = useNavigate();
  const doLogout = useAuthStore((s) => s.logout);

  const handleLogout = async () => {
    try {
      await logout();
    } finally {
      doLogout();
      nav('/login');
    }
  };

  return (
    <Navbar bg="white" className="border-bottom px-3">
      <Container fluid className="justify-content-end">
        <Button variant="outline-secondary" size="sm" onClick={handleLogout}>
          <FiLogOut className="me-1" /> Logout
        </Button>
      </Container>
    </Navbar>
  );
}
