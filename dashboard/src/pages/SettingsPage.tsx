import { useState } from 'react';
import { Card, Form, Button, Row, Col, Spinner } from 'react-bootstrap';
import { useSettings, useUpdatePreferences } from '../hooks/useSettings';
import { useMe } from '../hooks/useAuth';
import { changePassword } from '../api/auth';
import MfaSetup from '../components/auth/MfaSetup';
import toast from 'react-hot-toast';
import { useQueryClient } from '@tanstack/react-query';

export default function SettingsPage() {
  const { data: settings, isLoading } = useSettings();
  const { data: me } = useMe();
  const updatePrefs = useUpdatePreferences();
  const qc = useQueryClient();

  const [language, setLanguage] = useState('');
  const [timezone, setTimezone] = useState('');
  const [oldPwd, setOldPwd] = useState('');
  const [newPwd, setNewPwd] = useState('');

  if (isLoading) return <Spinner />;

  const handleSavePrefs = async (e: React.FormEvent) => {
    e.preventDefault();
    const updates: Record<string, unknown> = {};
    if (language) updates.language = language;
    if (timezone) updates.timezone = timezone;
    await updatePrefs.mutateAsync(updates);
    toast.success('Preferences saved');
  };

  const handleChangePassword = async (e: React.FormEvent) => {
    e.preventDefault();
    const result = await changePassword(oldPwd, newPwd);
    if (result.success) {
      toast.success('Password changed');
      setOldPwd('');
      setNewPwd('');
    } else {
      toast.error('Failed to change password');
    }
  };

  return (
    <div>
      <h4 className="mb-4">Settings</h4>
      <Row className="g-3">
        <Col md={6}>
          <Card className="mb-3">
            <Card.Body>
              <Card.Title>General</Card.Title>
              <Form onSubmit={handleSavePrefs}>
                <Form.Group className="mb-3">
                  <Form.Label>Language</Form.Label>
                  <Form.Control
                    type="text"
                    defaultValue={settings?.language ?? 'en'}
                    onChange={(e) => setLanguage(e.target.value)}
                    placeholder="en"
                  />
                </Form.Group>
                <Form.Group className="mb-3">
                  <Form.Label>Timezone</Form.Label>
                  <Form.Control
                    type="text"
                    defaultValue={settings?.timezone ?? 'UTC'}
                    onChange={(e) => setTimezone(e.target.value)}
                    placeholder="UTC"
                  />
                </Form.Group>
                <Form.Group className="mb-3">
                  <Form.Label>Model Tier</Form.Label>
                  <Form.Select
                    defaultValue={settings?.modelTier ?? 'balanced'}
                    onChange={(e) => updatePrefs.mutate({ modelTier: e.target.value })}
                  >
                    <option value="balanced">Balanced</option>
                    <option value="smart">Smart</option>
                    <option value="coding">Coding</option>
                    <option value="deep">Deep</option>
                  </Form.Select>
                </Form.Group>
                <Button type="submit" variant="primary" size="sm">
                  Save
                </Button>
              </Form>
            </Card.Body>
          </Card>
        </Col>

        <Col md={6}>
          <MfaSetup
            mfaEnabled={me?.mfaEnabled ?? false}
            onUpdate={() => qc.invalidateQueries({ queryKey: ['auth', 'me'] })}
          />

          <Card>
            <Card.Body>
              <Card.Title>Change Password</Card.Title>
              <Form onSubmit={handleChangePassword}>
                <Form.Group className="mb-3">
                  <Form.Label>Current Password</Form.Label>
                  <Form.Control
                    type="password"
                    value={oldPwd}
                    onChange={(e) => setOldPwd(e.target.value)}
                    required
                  />
                </Form.Group>
                <Form.Group className="mb-3">
                  <Form.Label>New Password</Form.Label>
                  <Form.Control
                    type="password"
                    value={newPwd}
                    onChange={(e) => setNewPwd(e.target.value)}
                    required
                  />
                </Form.Group>
                <Button type="submit" variant="warning" size="sm">
                  Change Password
                </Button>
              </Form>
            </Card.Body>
          </Card>
        </Col>
      </Row>
    </div>
  );
}
