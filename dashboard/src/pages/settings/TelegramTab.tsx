import { type ReactElement, useEffect, useMemo, useState } from 'react';
import { Badge, Button, Card, Form, InputGroup, Table } from 'react-bootstrap';
import toast from 'react-hot-toast';
import HelpTip from '../../components/common/HelpTip';
import ConfirmModal from '../../components/common/ConfirmModal';
import {
  useDeleteInviteCode,
  useGenerateInviteCode,
  useRestartTelegram,
  useUpdateTelegram,
  useUpdateVoice,
} from '../../hooks/useSettings';
import type { TelegramConfig, VoiceConfig } from '../../api/settings';
import { SaveStateHint, SettingsSaveBar } from '../../components/common/SettingsSaveBar';

function hasDiff<T>(current: T, initial: T): boolean {
  return JSON.stringify(current) !== JSON.stringify(initial);
}

function toNullableString(value: string): string | null {
  return value.length > 0 ? value : null;
}

interface TelegramTabProps {
  config: TelegramConfig;
  voiceConfig: VoiceConfig;
}

export default function TelegramTab({ config, voiceConfig }: TelegramTabProps): ReactElement {
  const updateTelegram = useUpdateTelegram();
  const updateVoice = useUpdateVoice();
  const genInvite = useGenerateInviteCode();
  const delInvite = useDeleteInviteCode();
  const restart = useRestartTelegram();

  const [enabled, setEnabled] = useState(config.enabled ?? false);
  const [token, setToken] = useState(config.token ?? '');
  const [showToken, setShowToken] = useState(false);
  const [authMode, setAuthMode] = useState(config.authMode ?? 'invite_only');
  const [allowedUserId, setAllowedUserId] = useState((config.allowedUsers?.[0] ?? '').replace(/\D/g, ''));
  const [telegramRespondWithVoice, setTelegramRespondWithVoice] = useState(voiceConfig.telegramRespondWithVoice ?? false);
  const [telegramTranscribeIncoming, setTelegramTranscribeIncoming] = useState(voiceConfig.telegramTranscribeIncoming ?? false);
  const [revokeCode, setRevokeCode] = useState<string | null>(null);

  useEffect(() => {
    setEnabled(config.enabled ?? false);
    setToken(config.token ?? '');
    setAuthMode(config.authMode ?? 'invite_only');
    setAllowedUserId((config.allowedUsers?.[0] ?? '').replace(/\D/g, ''));
  }, [config]);

  useEffect(() => {
    setTelegramRespondWithVoice(voiceConfig.telegramRespondWithVoice ?? false);
    setTelegramTranscribeIncoming(voiceConfig.telegramTranscribeIncoming ?? false);
  }, [voiceConfig]);

  const currentConfig = useMemo(
    () => ({
      ...config,
      enabled,
      token: toNullableString(token),
      authMode,
      allowedUsers: allowedUserId.length > 0 ? [allowedUserId] : [],
    }),
    [config, enabled, token, authMode, allowedUserId],
  );

  const initialConfig = useMemo(
    () => ({
      ...config,
      enabled: config.enabled ?? false,
      token: config.token ?? null,
      authMode: config.authMode ?? 'invite_only',
      allowedUsers: (config.allowedUsers?.[0] ?? '').replace(/\D/g, '').length > 0
        ? [(config.allowedUsers?.[0] ?? '').replace(/\D/g, '')]
        : [],
    }),
    [config],
  );

  const isTelegramDirty = hasDiff(currentConfig, initialConfig);

  const handleSave = async (): Promise<void> => {
    await updateTelegram.mutateAsync(currentConfig);
    await updateVoice.mutateAsync({
      ...voiceConfig,
      telegramRespondWithVoice,
      telegramTranscribeIncoming,
    });
    toast.success('Telegram settings saved');
  };

  const handleRevokeCode = async (): Promise<void> => {
    if (revokeCode == null || revokeCode.length === 0) {
      return;
    }
    await delInvite.mutateAsync(revokeCode);
    setRevokeCode(null);
    toast.success('Revoked');
  };

  return (
    <Card className="settings-card">
      <Card.Body>
        <Form.Check type="switch" label="Enable Telegram Bot" checked={enabled}
          onChange={(e) => setEnabled(e.target.checked)} className="mb-3" />

        <Form.Group className="mb-3">
          <Form.Label className="small fw-medium">
            Bot Token <HelpTip text="Telegram Bot API token from @BotFather" />
          </Form.Label>
          <InputGroup size="sm">
            <Form.Control
              type={showToken ? 'text' : 'password'}
              value={token}
              onChange={(e) => setToken(e.target.value)}
              placeholder="123456:ABC-DEF..."
            />
            <Button type="button" variant="secondary" onClick={() => setShowToken(!showToken)}>
              {showToken ? 'Hide' : 'Show'}
            </Button>
          </InputGroup>
        </Form.Group>

        <Form.Group className="mb-3">
          <Form.Label className="small fw-medium">
            Auth Mode <HelpTip text="Controls how users authenticate: user (single explicit ID) or invite codes (shareable codes)" />
          </Form.Label>
          <Form.Select
            size="sm"
            value={authMode}
            onChange={(e) => setAuthMode(e.target.value as 'user' | 'invite_only')}
          >
            <option value="user">User</option>
            <option value="invite_only">Invite Only</option>
          </Form.Select>
        </Form.Group>

        {authMode === 'user' && (
          <Form.Group className="mb-3">
            <Form.Label className="small fw-medium">
              Allowed User ID <HelpTip text="Single Telegram numeric user ID" />
            </Form.Label>
            <Form.Control
              size="sm"
              type="text"
              inputMode="numeric"
              pattern="[0-9]*"
              value={allowedUserId}
              onChange={(e) => setAllowedUserId(e.target.value.replace(/\D/g, ''))}
            />
          </Form.Group>
        )}

        {authMode === 'invite_only' && (
          <div className="mb-3">
            <div className="d-flex align-items-center justify-content-between mb-2">
              <span className="small fw-medium">Invite Codes <HelpTip text="Single-use codes that grant Telegram access when redeemed" /></span>
              <Button type="button" variant="primary" size="sm"
                onClick={() => genInvite.mutate(undefined, { onSuccess: () => toast.success('Invite code generated') })}>
                Generate Code
              </Button>
            </div>
            {(config.inviteCodes ?? []).length > 0 ? (
              <Table size="sm" hover responsive className="mb-0 dashboard-table responsive-table invites-table">
                <thead>
                  <tr>
                    <th scope="col">Code</th>
                    <th scope="col">Status</th>
                    <th scope="col">Created</th>
                    <th scope="col">Actions</th>
                  </tr>
                </thead>
                <tbody>
                  {(config.inviteCodes ?? []).map((ic) => (
                    <tr key={ic.code}>
                      <td data-label="Code"><code className="small">{ic.code}</code></td>
                      <td data-label="Status"><Badge bg={ic.used ? 'secondary' : 'success'}>{ic.used ? 'Used' : 'Active'}</Badge></td>
                      <td data-label="Created" className="small text-body-secondary">{new Date(ic.createdAt).toLocaleDateString()}</td>
                      <td data-label="Actions" className="text-end">
                        <div className="d-flex flex-wrap gap-1 invite-actions">
                          <Button type="button"
                            size="sm"
                            variant="secondary"
                            className="invite-action-btn"
                            onClick={() => {
                              void navigator.clipboard.writeText(ic.code);
                              toast.success('Copied!');
                            }}
                          >
                            Copy
                          </Button>
                          <Button type="button"
                            size="sm"
                            variant="danger"
                            className="invite-action-btn"
                            onClick={() => setRevokeCode(ic.code)}
                          >
                            Revoke
                          </Button>
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </Table>
            ) : (
              <p className="text-body-secondary small mb-0">No invite codes yet</p>
            )}
            {(config.allowedUsers ?? []).length > 0 && (
              <div className="mt-3">
                <span className="small fw-medium">Registered Users</span>
                <div className="mt-1">
                  {config.allowedUsers.map((uid) => (
                    <Badge key={uid} bg="info" className="me-1">{uid}</Badge>
                  ))}
                </div>
              </div>
            )}
          </div>
        )}

        <Card className="settings-card mt-3">
          <Card.Body>
            <Card.Title className="h6 mb-2">Telegram Voice</Card.Title>
            <Form.Check type="switch"
              label={<>Respond with Voice <HelpTip text="If incoming message is voice, bot can answer with synthesized voice." /></>}
              checked={telegramRespondWithVoice}
              onChange={(e) => setTelegramRespondWithVoice(e.target.checked)}
              className="mb-2"
            />
            <Form.Check type="switch"
              label={<>Transcribe Incoming Voice <HelpTip text="Enable transcription of incoming voice messages before processing." /></>}
              checked={telegramTranscribeIncoming}
              onChange={(e) => setTelegramTranscribeIncoming(e.target.checked)}
            />
          </Card.Body>
        </Card>

        <SettingsSaveBar className="flex-wrap">
          <Button type="button" variant="primary" size="sm" onClick={() => { void handleSave(); }} disabled={!isTelegramDirty || updateTelegram.isPending}>
            {updateTelegram.isPending ? 'Saving...' : 'Save'}
          </Button>
          <Button type="button" variant="warning" size="sm"
            disabled={restart.isPending}
            onClick={() => restart.mutate(undefined, {
              onSuccess: () => toast.success('Telegram bot restarted'),
              onError: (err) => toast.error(`Failed to restart: ${err instanceof Error ? err.message : 'Unknown error'}`),
            })}>
            {restart.isPending ? 'Restarting...' : 'Restart Bot'}
          </Button>
          <SaveStateHint isDirty={isTelegramDirty} />
        </SettingsSaveBar>
      </Card.Body>

      <ConfirmModal
        show={revokeCode != null && revokeCode.length > 0}
        title="Revoke Invite Code"
        message="This invite code will stop working immediately. This action cannot be undone."
        confirmLabel="Revoke"
        confirmVariant="danger"
        isProcessing={delInvite.isPending}
        onConfirm={() => { void handleRevokeCode(); }}
        onCancel={() => setRevokeCode(null)}
      />
    </Card>
  );
}
