import { type ReactElement, useEffect, useRef, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { Button, Card, Container } from '../components/ui/tailwind-components';
import { exchangeHiveSsoCode, getHiveSsoStatus, getMfaStatus, login, type HiveSsoStatus } from '../api/auth';
import { useAuthStore } from '../store/authStore';
import LoginForm from '../components/auth/LoginForm';

const PKCE_CODE_BYTE_LENGTH = 32;
const HIVE_SSO_PKCE_PREFIX = 'hive-sso-pkce:';
const S256_CHALLENGE_METHOD = 'S256';

function shouldOfferHiveSsoChoice(hiveSso: HiveSsoStatus | null): boolean {
  if (hiveSso == null) {
    return false;
  }
  return hiveSso.enabled && hiveSso.available && hiveSso.loginUrl != null;
}

function encodeBase64Url(bytes: Uint8Array): string {
  const binary = String.fromCharCode(...bytes);
  return window.btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/u, '');
}

function createPkceRandomValue(): string {
  const bytes = new Uint8Array(PKCE_CODE_BYTE_LENGTH);
  window.crypto.getRandomValues(bytes);
  return encodeBase64Url(bytes);
}

async function createS256Challenge(codeVerifier: string): Promise<string> {
  const data = new TextEncoder().encode(codeVerifier);
  const digest = await window.crypto.subtle.digest('SHA-256', data);
  return encodeBase64Url(new Uint8Array(digest));
}

function buildPkceStorageKey(state: string): string {
  return `${HIVE_SSO_PKCE_PREFIX}${state}`;
}

export default function LoginPage(): ReactElement {
  const [mfaRequired, setMfaRequired] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [hiveSso, setHiveSso] = useState<HiveSsoStatus | null>(null);
  const ssoExchangeCodeRef = useRef<string | null>(null);
  const passwordInputRef = useRef<HTMLInputElement | null>(null);
  const setAccessToken = useAuthStore((s) => s.setAccessToken);
  const token = useAuthStore((s) => s.accessToken);
  const nav = useNavigate();
  const location = useLocation();
  const shouldShowSsoChoice = shouldOfferHiveSsoChoice(hiveSso);
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

  // Exchange the one-time Hive OAuth code from the callback query string exactly once.
  useEffect(() => {
    const params = new URLSearchParams(location.search);
    const code = params.get('code');
    const state = params.get('state');
    if (code == null || code.length === 0 || ssoExchangeCodeRef.current === code) {
      return;
    }
    if (state == null || state.length === 0) {
      setError('Hive SSO state is missing');
      nav('/login', { replace: true });
      return;
    }
    const storageKey = buildPkceStorageKey(state);
    const codeVerifier = window.sessionStorage.getItem(storageKey);
    if (codeVerifier == null || codeVerifier.length === 0) {
      setError('Hive SSO verifier is missing');
      nav('/login', { replace: true });
      return;
    }
    ssoExchangeCodeRef.current = code;
    window.sessionStorage.removeItem(storageKey);
    setLoading(true);
    exchangeHiveSsoCode(code, codeVerifier)
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

  const handleHiveSsoLogin = async (): Promise<void> => {
    if (hiveSsoLoginUrl == null) {
      return;
    }
    try {
      const codeVerifier = createPkceRandomValue();
      const state = createPkceRandomValue();
      const codeChallenge = await createS256Challenge(codeVerifier);
      window.sessionStorage.setItem(buildPkceStorageKey(state), codeVerifier);
      const authorizeUrl = new URL(hiveSsoLoginUrl, window.location.origin);
      authorizeUrl.searchParams.set('code_challenge', codeChallenge);
      authorizeUrl.searchParams.set('code_challenge_method', S256_CHALLENGE_METHOD);
      authorizeUrl.searchParams.set('state', state);
      window.location.assign(authorizeUrl.toString());
    } catch (error: unknown) {
      console.error('Failed to prepare Hive SSO PKCE challenge', error);
      setError('Hive SSO setup failed');
    }
  };

  const focusPasswordLogin = (): void => {
    setError(null);
    passwordInputRef.current?.focus();
  };

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
          {shouldShowSsoChoice && (
            <div className="d-grid gap-2 mb-3">
              <Button type="button" variant="primary" onClick={() => { void handleHiveSsoLogin(); }}>
                Continue with Hive SSO
              </Button>
              <Button type="button" variant="secondary" onClick={focusPasswordLogin}>
                Use password instead
              </Button>
            </div>
          )}
          {hiveSso?.enabled === true && !hiveSso.available && hiveSso.reason != null && (
            <div className="text-body-secondary small mb-3">Hive SSO unavailable: {hiveSso.reason}</div>
          )}
          <LoginForm
            mfaRequired={mfaRequired}
            onSubmit={handleSubmit}
            error={error}
            loading={loading}
            passwordInputRef={passwordInputRef}
          />
        </Card.Body>
      </Card>
    </Container>
  );
}
