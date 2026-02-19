import { useState } from 'react';
import { Form, Button, Alert, Card } from 'react-bootstrap';
import { setupMfa, enableMfa, disableMfa } from '../../api/auth';
import toast from 'react-hot-toast';

interface Props {
  mfaEnabled: boolean;
  onUpdate: () => void;
}

export default function MfaSetup({ mfaEnabled, onUpdate }: Props) {
  const [secret, setSecret] = useState('');
  const [qrUri, setQrUri] = useState('');
  const [code, setCode] = useState('');
  const [disablePassword, setDisablePassword] = useState('');
  const [setting, setSetting] = useState(false);

  const handleSetup = async () => {
    const result = await setupMfa();
    setSecret(result.secret);
    setQrUri(result.qrUri);
    setSetting(true);
  };

  const handleEnable = async (e: React.FormEvent) => {
    e.preventDefault();
    const result = await enableMfa(secret, code);
    if (result.success) {
      toast.success('MFA enabled');
      setSetting(false);
      onUpdate();
    } else {
      toast.error('Invalid code');
    }
  };

  const handleDisable = async (e: React.FormEvent) => {
    e.preventDefault();
    const result = await disableMfa(disablePassword);
    if (result.success) {
      toast.success('MFA disabled');
      onUpdate();
    } else {
      toast.error('Wrong password');
    }
  };

  if (mfaEnabled) {
    return (
      <Card className="mb-3">
        <Card.Body>
          <Card.Title>MFA Enabled</Card.Title>
          <Form onSubmit={handleDisable}>
            <Form.Group className="mb-3">
              <Form.Label>Enter password to disable MFA</Form.Label>
              <Form.Control
                type="password"
                value={disablePassword}
                onChange={(e) => setDisablePassword(e.target.value)}
                autoComplete="current-password"
                required
              />
            </Form.Group>
            <Button type="submit" variant="danger" size="sm">
              Disable MFA
            </Button>
          </Form>
        </Card.Body>
      </Card>
    );
  }

  if (setting) {
    return (
      <Card className="mb-3">
        <Card.Body>
          <Card.Title>Setup MFA</Card.Title>
          <p className="text-body-secondary small">
            Scan this URI in your authenticator app:
          </p>
          <Alert variant="info" className="text-break small">{qrUri}</Alert>
          <p className="text-body-secondary small">Secret: <code>{secret}</code></p>
          <Form onSubmit={handleEnable}>
            <Form.Group className="mb-3">
              <Form.Label>Verification Code</Form.Label>
              <Form.Control
                type="text"
                value={code}
                onChange={(e) => setCode(e.target.value)}
                placeholder="6-digit code"
                autoComplete="one-time-code"
                inputMode="numeric"
                maxLength={6}
                required
              />
            </Form.Group>
            <Button type="submit" variant="primary" size="sm">
              Enable MFA
            </Button>
          </Form>
        </Card.Body>
      </Card>
    );
  }

  return (
    <Card className="mb-3">
      <Card.Body>
        <Card.Title>Multi-Factor Authentication</Card.Title>
        <p className="text-body-secondary">MFA is not enabled.</p>
        <Button type="button" variant="primary" size="sm" onClick={handleSetup}>
          Setup MFA
        </Button>
      </Card.Body>
    </Card>
  );
}
