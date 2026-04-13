import { type ReactElement, useEffect, useRef, useState } from 'react';
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
  const ssoExchangeCodeRef = useRef<string | null>(null);
  const setAccessToken = useAuthStore((s) => s.setAccessToken);
  const token = useAuthStore((s) => s.accessToken);
  const nav = useNavigate();
  const location = useLocation();

  // Keep authenticated users out of the login page.
  useEffect(() => {
    if (token != null && token.length > 0) {
      nav('/', { replace: true });
    }
  }, [token, nav]);

  // Load public login prerequisites once for the login form and SSO entrypoint.
  useEffect(() => {
    getMfaStatus()
      .then((r) => setMfaRequired(r.mfaRequired))
      .catch((error: unknown) => console.error('Failed to load MFA status', error));
    getHiveSsoStatus()
      .then(setHiveSso)
      .catch((error: unknown) => console.error('Failed to load Hive SSO status', error));
  }, []);

  // Exchange the one-time Hive OAuth code from the callback query string exactly once.
  useEffect(() => {
    const params = new URLSearchParams(location.search);
    const code = params.get('code');
    if (code == null || code.length === 0 || ssoExchangeCodeRef.current === code) {
      return;
    }
    ssoExchangeCodeRef.current = code;
    setLoading(true);
    exchangeHiveSsoCode(code)
      .then((result) => {
        setAccessToken(result.accessToken);
        nav('/', { replace: true });
      })
      .catch((error: unknown) => {
        console.error('Hive SSO failed', error);
        setError('Hive SSO failed');
        nav('/login', { replace: true });
      })
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
