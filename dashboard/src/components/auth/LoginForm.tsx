import { useState } from 'react';
import { Form, Button, Alert, Spinner } from 'react-bootstrap';

interface Props {
  mfaRequired: boolean;
  onSubmit: (password: string, mfaCode?: string) => Promise<void>;
  error: string | null;
  loading: boolean;
}

export default function LoginForm({ mfaRequired, onSubmit, error, loading }: Props) {
  const [password, setPassword] = useState('');
  const [mfaCode, setMfaCode] = useState('');
  const [step, setStep] = useState<1 | 2>(1);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (mfaRequired && step === 1) {
      setStep(2);
      return;
    }
    await onSubmit(password, mfaRequired ? mfaCode : undefined);
  };

  return (
    <Form onSubmit={handleSubmit}>
      {error && <Alert variant="danger">{error}</Alert>}

      {step === 1 && (
        <Form.Group className="mb-3">
          <Form.Label>Password</Form.Label>
          <Form.Control
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            placeholder="Enter admin password"
            autoComplete="current-password"
            required
            autoFocus
          />
        </Form.Group>
      )}

      {step === 2 && mfaRequired && (
        <Form.Group className="mb-3">
          <Form.Label>TOTP Code</Form.Label>
          <Form.Control
            type="text"
            value={mfaCode}
            onChange={(e) => setMfaCode(e.target.value)}
            placeholder="Enter 6-digit code"
            autoComplete="one-time-code"
            inputMode="numeric"
            maxLength={6}
            required
            autoFocus
          />
        </Form.Group>
      )}

      <Button type="submit" variant="primary" className="w-100" disabled={loading}>
        {loading ? <Spinner size="sm" /> : mfaRequired && step === 1 ? 'Next' : 'Login'}
      </Button>
    </Form>
  );
}
