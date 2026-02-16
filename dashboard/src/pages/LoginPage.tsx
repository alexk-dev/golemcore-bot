import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { Card, Container } from 'react-bootstrap';
import { getMfaStatus, login } from '../api/auth';
import { useAuthStore } from '../store/authStore';
import LoginForm from '../components/auth/LoginForm';

export default function LoginPage() {
  const [mfaRequired, setMfaRequired] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const setAccessToken = useAuthStore((s) => s.setAccessToken);
  const token = useAuthStore((s) => s.accessToken);
  const nav = useNavigate();

  useEffect(() => {
    if (token) {nav('/', { replace: true });}
  }, [token, nav]);

  useEffect(() => {
    getMfaStatus().then((r) => setMfaRequired(r.mfaRequired)).catch(() => {});
  }, []);

  const handleSubmit = async (password: string, mfaCode?: string) => {
    setLoading(true);
    setError(null);
    try {
      const result = await login(password, mfaCode);
      setAccessToken(result.accessToken);
      nav('/', { replace: true });
    } catch {
      setError('Invalid credentials');
    } finally {
      setLoading(false);
    }
  };

  return (
    <Container className="login-page d-flex align-items-center justify-content-center">
      <Card className="login-card shadow-sm">
        <Card.Body>
          <h4 className="text-center mb-4">GolemCore Dashboard</h4>
          <LoginForm
            mfaRequired={mfaRequired}
            onSubmit={handleSubmit}
            error={error}
            loading={loading}
          />
        </Card.Body>
      </Card>
    </Container>
  );
}
