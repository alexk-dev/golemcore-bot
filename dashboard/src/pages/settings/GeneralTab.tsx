import { type ReactElement, useEffect, useState } from 'react';
import type { QueryClient } from '@tanstack/react-query';
import { Button, Card, Col, Form, Row } from 'react-bootstrap';
import toast from 'react-hot-toast';
import HelpTip from '../../components/common/HelpTip';
import SettingsCardTitle from '../../components/common/SettingsCardTitle';
import { useUpdatePreferences } from '../../hooks/useSettings';
import { changePassword } from '../../api/auth';
import MfaSetup from '../../components/auth/MfaSetup';
import { SaveStateHint, SettingsSaveBar } from '../../components/common/SettingsSaveBar';

export interface GeneralSettingsData {
  language?: string;
  timezone?: string;
}

export interface AuthMe {
  mfaEnabled?: boolean;
}

export interface GeneralTabProps {
  settings: GeneralSettingsData | undefined;
  me: AuthMe | undefined;
  qc: QueryClient;
}

export default function GeneralTab({ settings, me, qc }: GeneralTabProps): ReactElement {
  const updatePrefs = useUpdatePreferences();
  const [language, setLanguage] = useState(settings?.language ?? 'en');
  const [timezone, setTimezone] = useState(settings?.timezone ?? 'UTC');
  const [oldPwd, setOldPwd] = useState('');
  const [newPwd, setNewPwd] = useState('');

  // Keep editable fields aligned with loaded preferences.
  useEffect(() => {
    setLanguage(settings?.language ?? 'en');
    setTimezone(settings?.timezone ?? 'UTC');
  }, [settings]);

  const isPrefsDirty = language !== (settings?.language ?? 'en') || timezone !== (settings?.timezone ?? 'UTC');

  const handleSavePrefs = async (e: React.FormEvent): Promise<void> => {
    e.preventDefault();
    await updatePrefs.mutateAsync({ language, timezone });
    toast.success('Preferences saved');
  };

  const handleSavePrefsSubmit = (e: React.FormEvent): void => {
    void handleSavePrefs(e);
  };

  const handleChangePassword = async (e: React.FormEvent): Promise<void> => {
    e.preventDefault();
    const result = await changePassword(oldPwd, newPwd);
    if (result.success) {
      toast.success('Password changed');
      setOldPwd('');
      setNewPwd('');
      return;
    }
    toast.error('Failed to change password');
  };

  const handleChangePasswordSubmit = (e: React.FormEvent): void => {
    void handleChangePassword(e);
  };

  return (
    <Row className="g-3">
      <Col lg={6}>
        <Card className="settings-card">
          <Card.Body>
            <SettingsCardTitle title="Preferences" />
            <Form onSubmit={handleSavePrefsSubmit}>
              <Form.Group className="mb-3">
                <Form.Label className="small fw-medium">
                  Language <HelpTip text="UI and bot response language" />
                </Form.Label>
                <Form.Select size="sm" value={language} onChange={(e) => setLanguage(e.target.value)}>
                  <option value="en">English</option>
                  <option value="ru">Russian</option>
                </Form.Select>
              </Form.Group>
              <Form.Group className="mb-3">
                <Form.Label className="small fw-medium">
                  Timezone <HelpTip text="Used for scheduling and timestamp display" />
                </Form.Label>
                <Form.Control
                  size="sm"
                  type="text"
                  value={timezone}
                  onChange={(e) => setTimezone(e.target.value)}
                  placeholder="UTC"
                />
              </Form.Group>
              <SettingsSaveBar>
                <Button type="submit" variant="primary" size="sm" disabled={!isPrefsDirty || updatePrefs.isPending}>
                  {updatePrefs.isPending ? 'Saving...' : 'Save Preferences'}
                </Button>
                <SaveStateHint isDirty={isPrefsDirty} />
              </SettingsSaveBar>
            </Form>
          </Card.Body>
        </Card>
      </Col>
      <Col lg={6}>
        <MfaSetup
          mfaEnabled={me?.mfaEnabled ?? false}
          onUpdate={() => { void qc.invalidateQueries({ queryKey: ['auth', 'me'] }); }}
        />
        <Card className="settings-card mt-3">
          <Card.Body>
            <SettingsCardTitle title="Change Password" />
            <Form onSubmit={handleChangePasswordSubmit}>
              <Form.Group className="mb-3">
                <Form.Label className="small fw-medium">Current Password</Form.Label>
                <Form.Control
                  size="sm"
                  type="password"
                  value={oldPwd}
                  onChange={(e) => setOldPwd(e.target.value)}
                  autoComplete="current-password"
                  required
                />
              </Form.Group>
              <Form.Group className="mb-3">
                <Form.Label className="small fw-medium">New Password</Form.Label>
                <Form.Control
                  size="sm"
                  type="password"
                  value={newPwd}
                  onChange={(e) => setNewPwd(e.target.value)}
                  autoComplete="new-password"
                  required
                />
              </Form.Group>
              <Button type="submit" variant="warning" size="sm">Change Password</Button>
            </Form>
          </Card.Body>
        </Card>
      </Col>
    </Row>
  );
}
