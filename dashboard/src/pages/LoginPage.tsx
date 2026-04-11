import { type ReactElement, useState, useEffect } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { Card, Container } from 'react-bootstrap';
import { exchangeHiveSsoCode, getHiveSsoStatus, getMfaStatus, login, type HiveSsoStatus } from '../api/auth';
import { useAuthStore } from '../store/authStore';
import LoginForm from '../components/auth/LoginForm';

export default function LoginPage(): ReactElement {
  const [mfaRequired, setMfaRequired] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [hiveSso, setHiveSso] = useState<HiveSsoStatus | null>(null);
  const setAccessToken = useAuthStore((s) => s.setAccessToken);
  const token = useAuthStore((s) => s.accessToken);
  const nav = useNavigate();
  const location = useLocation();

  useEffect(() => {
    if (token != null && token.length > 0) {
      nav('/', { replace: true });
    }
  }, [token, nav]);

  useEffect(() => {
    getMfaStatus().then((r) => setMfaRequired(r.mfaRequired)).catch(() => {});
    getHiveSsoStatus().then(setHiveSso).catch(() => {});
  }, []);

  useEffect(() => {
    const params = new URLSearchParams(location.search);
    const code = params.get('code');
    if (code == null || code.length === 0) {
      return;
    }
    setLoading(true);
    exchangeHiveSsoCode(code)
      .then((result) => {
        setAccessToken(result.accessToken);
        nav('/', { replace: true });
      })
      .catch(() => setError('Hive SSO failed'))
      .finally(() => setLoading(false));
  }, [location.search, nav, setAccessToken]);

  const handleSubmit = async (password: string, mfaCode?: string): Promise<void> => {
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
          {hiveSso?.available === true && hiveSso.loginUrl != null && (
            <a className="btn btn-primary w-100 mb-3" href={hiveSso.loginUrl}>
              Login with Hive SSO
            </a>
          )}
          {hiveSso?.enabled === true && !hiveSso.available && hiveSso.reason != null && (
            <div className="text-body-secondary small mb-3">Hive SSO unavailable: {hiveSso.reason}</div>
          )}
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
