import { type ReactElement, useEffect, useRef, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { Button, Card, Container } from 'react-bootstrap';
import { exchangeHiveSsoCode, getHiveSsoStatus, getMfaStatus, login, type HiveSsoStatus } from '../api/auth';
import { useAuthStore } from '../store/authStore';
import LoginForm from '../components/auth/LoginForm';

type LoginMethod = 'choice' | 'password';

function shouldOfferHiveSsoChoice(hiveSso: HiveSsoStatus | null): boolean {
  if (hiveSso == null) {
    return false;
  }
  return hiveSso.enabled && hiveSso.available && hiveSso.loginUrl != null;
}

export default function LoginPage(): ReactElement {
  const [mfaRequired, setMfaRequired] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [hiveSso, setHiveSso] = useState<HiveSsoStatus | null>(null);
  const [loginMethod, setLoginMethod] = useState<LoginMethod>('choice');
  const ssoExchangeCodeRef = useRef<string | null>(null);
  const setAccessToken = useAuthStore((s) => s.setAccessToken);
  const token = useAuthStore((s) => s.accessToken);
  const nav = useNavigate();
  const location = useLocation();
  const shouldShowSsoChoice = shouldOfferHiveSsoChoice(hiveSso);
  const shouldShowPasswordForm = loginMethod === 'password' || !shouldShowSsoChoice;
  const hiveSsoLoginUrl = shouldShowSsoChoice ? hiveSso?.loginUrl : null;

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

  // Return to the method choice when Hive SSO becomes active again after using password fallback.
  useEffect(() => {
    if (shouldShowSsoChoice && loginMethod === 'password') {
      setLoginMethod('choice');
    }
  }, [loginMethod, shouldShowSsoChoice]);

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
        setLoginMethod('password');
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
          {shouldShowSsoChoice && loginMethod === 'choice' && (
            <div className="d-grid gap-2 mb-3">
              <a className="btn btn-primary" href={hiveSsoLoginUrl ?? undefined}>
                Continue with Hive SSO
              </a>
              <Button type="button" variant="secondary" onClick={() => setLoginMethod('password')}>
                Use password instead
              </Button>
            </div>
          )}
          {shouldShowSsoChoice && loginMethod === 'password' && (
            <Button
              type="button"
              variant="secondary"
              className="w-100 mb-3"
              onClick={() => {
                setError(null);
                setLoginMethod('choice');
              }}
            >
              Back to Hive SSO
            </Button>
          )}
          {hiveSso?.enabled === true && !hiveSso.available && hiveSso.reason != null && (
            <div className="text-body-secondary small mb-3">Hive SSO unavailable: {hiveSso.reason}</div>
          )}
          {shouldShowPasswordForm && (
            <LoginForm
              mfaRequired={mfaRequired}
              onSubmit={handleSubmit}
              error={error}
              loading={loading}
            />
          )}
        </Card.Body>
      </Card>
    </Container>
  );
}
