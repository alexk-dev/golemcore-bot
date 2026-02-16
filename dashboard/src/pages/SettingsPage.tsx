import { useState, useEffect, useMemo } from 'react';
import {
  Card, Form, Button, Row, Col, Spinner, Tab, Tabs,
  Table, Badge, InputGroup, OverlayTrigger, Tooltip,
} from 'react-bootstrap';
import {
  useSettings, useUpdatePreferences, useRuntimeConfig,
  useUpdateTelegram, useUpdateModelRouter, useUpdateTools,
  useUpdateVoice, useUpdateWebhooks, useUpdateAuto, useUpdateAdvanced,
  useGenerateInviteCode, useDeleteInviteCode, useRestartTelegram,
} from '../hooks/useSettings';
import { useAvailableModels } from '../hooks/useModels';
import { useMe } from '../hooks/useAuth';
import { changePassword } from '../api/auth';
import MfaSetup from '../components/auth/MfaSetup';
import toast from 'react-hot-toast';
import { useQueryClient } from '@tanstack/react-query';
import type {
  TelegramConfig, ModelRouterConfig, ToolsConfig, VoiceConfig,
  AutoModeConfig, RateLimitConfig, SecurityConfig, CompactionConfig,
  WebhookConfig, HookMapping, ImapConfig, SmtpConfig,
} from '../api/settings';
import { FiHelpCircle } from 'react-icons/fi';

// ==================== Tooltip Helper ====================

function Tip({ text }: { text: string }) {
  return (
    <OverlayTrigger
      placement="top"
      overlay={<Tooltip>{text}</Tooltip>}
    >
      <span className="setting-tip"><FiHelpCircle /></span>
    </OverlayTrigger>
  );
}

// ==================== Main ====================

export default function SettingsPage() {
  const { data: settings, isLoading: settingsLoading } = useSettings();
  const { data: rc, isLoading: rcLoading } = useRuntimeConfig();
  const { data: me } = useMe();
  const qc = useQueryClient();

  if (settingsLoading || rcLoading) {
    return (
      <div className="d-flex justify-content-center align-items-center" style={{ minHeight: 300 }}>
        <Spinner animation="border" variant="primary" />
      </div>
    );
  }

  return (
    <div>
      <div className="page-header">
        <h4>Settings</h4>
        <p className="text-muted mb-0">Configure your GolemCore instance</p>
      </div>
      <Tabs defaultActiveKey="general" className="mb-3">
        <Tab eventKey="general" title="General">
          <GeneralTab settings={settings} me={me} qc={qc} />
        </Tab>
        <Tab eventKey="telegram" title="Telegram">
          {rc && <TelegramTab config={rc.telegram} />}
        </Tab>
        <Tab eventKey="models" title="Models">
          {rc && <ModelsTab config={rc.modelRouter} />}
        </Tab>
        <Tab eventKey="tools" title="Tools">
          {rc && <ToolsTab config={rc.tools} />}
        </Tab>
        <Tab eventKey="voice" title="Voice">
          {rc && <VoiceTab config={rc.voice} />}
        </Tab>
        <Tab eventKey="webhooks" title="Webhooks">
          <WebhooksTab />
        </Tab>
        <Tab eventKey="auto" title="Auto Mode">
          {rc && <AutoModeTab config={rc.autoMode} />}
        </Tab>
        <Tab eventKey="advanced" title="Advanced">
          {rc && <AdvancedTab rateLimit={rc.rateLimit} security={rc.security} compaction={rc.compaction} />}
        </Tab>
      </Tabs>
    </div>
  );
}

// ==================== General Tab ====================

function GeneralTab({ settings, me, qc }: { settings: any; me: any; qc: any }) {
  const updatePrefs = useUpdatePreferences();
  const [language, setLanguage] = useState('');
  const [timezone, setTimezone] = useState('');
  const [oldPwd, setOldPwd] = useState('');
  const [newPwd, setNewPwd] = useState('');

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
    <Row className="g-3">
      <Col lg={6}>
        <Card className="settings-card">
          <Card.Body>
            <Card.Title className="h6 mb-3">Preferences</Card.Title>
            <Form onSubmit={handleSavePrefs}>
              <Form.Group className="mb-3">
                <Form.Label className="small fw-medium">
                  Language <Tip text="UI and bot response language" />
                </Form.Label>
                <Form.Select
                  size="sm"
                  defaultValue={settings?.language ?? 'en'}
                  onChange={(e) => setLanguage(e.target.value)}
                >
                  <option value="en">English</option>
                  <option value="ru">Russian</option>
                </Form.Select>
              </Form.Group>
              <Form.Group className="mb-3">
                <Form.Label className="small fw-medium">
                  Timezone <Tip text="Used for scheduling and timestamp display" />
                </Form.Label>
                <Form.Control
                  size="sm"
                  type="text"
                  defaultValue={settings?.timezone ?? 'UTC'}
                  onChange={(e) => setTimezone(e.target.value)}
                  placeholder="UTC"
                />
              </Form.Group>
              <Button type="submit" variant="primary" size="sm">Save Preferences</Button>
            </Form>
          </Card.Body>
        </Card>
      </Col>
      <Col lg={6}>
        <MfaSetup
          mfaEnabled={me?.mfaEnabled ?? false}
          onUpdate={() => qc.invalidateQueries({ queryKey: ['auth', 'me'] })}
        />
        <Card className="settings-card mt-3">
          <Card.Body>
            <Card.Title className="h6 mb-3">Change Password</Card.Title>
            <Form onSubmit={handleChangePassword}>
              <Form.Group className="mb-3">
                <Form.Label className="small fw-medium">Current Password</Form.Label>
                <Form.Control size="sm" type="password" value={oldPwd} onChange={(e) => setOldPwd(e.target.value)} required />
              </Form.Group>
              <Form.Group className="mb-3">
                <Form.Label className="small fw-medium">New Password</Form.Label>
                <Form.Control size="sm" type="password" value={newPwd} onChange={(e) => setNewPwd(e.target.value)} required />
              </Form.Group>
              <Button type="submit" variant="warning" size="sm">Change Password</Button>
            </Form>
          </Card.Body>
        </Card>
      </Col>
    </Row>
  );
}

// ==================== Telegram Tab ====================

function TelegramTab({ config }: { config: TelegramConfig }) {
  const updateTelegram = useUpdateTelegram();
  const genInvite = useGenerateInviteCode();
  const delInvite = useDeleteInviteCode();
  const restart = useRestartTelegram();

  const [enabled, setEnabled] = useState(config.enabled ?? false);
  const [token, setToken] = useState(config.token ?? '');
  const [showToken, setShowToken] = useState(false);
  const [authMode, setAuthMode] = useState(config.authMode ?? 'invite');
  const [allowedUserId, setAllowedUserId] = useState((config.allowedUsers?.[0] ?? '').replace(/\D/g, ''));

  const handleSave = async () => {
    const users = allowedUserId ? [allowedUserId] : [];
    await updateTelegram.mutateAsync({
      ...config, enabled, token: token || null, authMode, allowedUsers: users,
    });
    toast.success('Telegram settings saved');
  };

  return (
    <Card className="settings-card">
      <Card.Body>
        <Form.Check type="switch" label="Enable Telegram Bot" checked={enabled}
          onChange={(e) => setEnabled(e.target.checked)} className="mb-3" />

        <Form.Group className="mb-3">
          <Form.Label className="small fw-medium">
            Bot Token <Tip text="Telegram Bot API token from @BotFather" />
          </Form.Label>
          <InputGroup size="sm">
            <Form.Control
              type={showToken ? 'text' : 'password'}
              value={token}
              onChange={(e) => setToken(e.target.value)}
              placeholder="123456:ABC-DEF..."
            />
            <Button variant="secondary" onClick={() => setShowToken(!showToken)}>
              {showToken ? 'Hide' : 'Show'}
            </Button>
          </InputGroup>
        </Form.Group>

        <Form.Group className="mb-3">
              <Form.Label className="small fw-medium">
                Auth Mode <Tip text="Controls how users authenticate: user (single explicit ID) or invite codes (shareable codes)" />
              </Form.Label>
              <Form.Select
                size="sm"
                value={authMode}
                onChange={(e) => setAuthMode(e.target.value as 'user' | 'invite')}
              >
                <option value="user">User</option>
                <option value="invite">Invite Codes</option>
              </Form.Select>
            </Form.Group>

        {authMode === 'user' && (
          <Form.Group className="mb-3">
            <Form.Label className="small fw-medium">
              Allowed User ID <Tip text="Single Telegram numeric user ID" />
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

        {authMode === 'invite' && (
          <div className="mb-3">
            <div className="d-flex align-items-center justify-content-between mb-2">
              <span className="small fw-medium">Invite Codes <Tip text="Single-use codes that grant Telegram access when redeemed" /></span>
              <Button variant="primary" size="sm"
                onClick={() => genInvite.mutate(undefined, { onSuccess: () => toast.success('Invite code generated') })}>
                Generate Code
              </Button>
            </div>
            {(config.inviteCodes ?? []).length > 0 ? (
              <Table size="sm" hover className="mb-0">
                <thead><tr><th>Code</th><th>Status</th><th>Created</th><th></th></tr></thead>
                <tbody>
                  {(config.inviteCodes ?? []).map((ic) => (
                    <tr key={ic.code}>
                      <td><code className="small">{ic.code}</code></td>
                      <td><Badge bg={ic.used ? 'secondary' : 'success'}>{ic.used ? 'Used' : 'Active'}</Badge></td>
                      <td className="small text-muted">{new Date(ic.createdAt).toLocaleDateString()}</td>
                      <td className="text-end">
                        <Button size="sm" variant="secondary" className="me-2"
                          onClick={() => { navigator.clipboard.writeText(ic.code); toast.success('Copied!'); }}>Copy</Button>
                        <Button size="sm" variant="danger"
                          onClick={() => delInvite.mutate(ic.code, { onSuccess: () => toast.success('Revoked') })}>Revoke</Button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </Table>
            ) : (
              <p className="text-muted small mb-0">No invite codes yet</p>
            )}
          </div>
        )}

        <div className="d-flex gap-2 pt-2 border-top">
          <Button variant="primary" size="sm" onClick={handleSave}>Save</Button>
          <Button variant="warning" size="sm"
            onClick={() => restart.mutate(undefined, { onSuccess: () => toast.success('Telegram restarting...') })}>
            Restart Bot
          </Button>
        </div>
      </Card.Body>
    </Card>
  );
}

// ==================== Models Tab ====================

interface AvailableModel {
  id: string;
  displayName: string;
  hasReasoning: boolean;
  reasoningLevels: string[];
}

function ModelsTab({ config }: { config: ModelRouterConfig }) {
  const updateRouter = useUpdateModelRouter();
  const { data: available } = useAvailableModels();
  const [form, setForm] = useState<ModelRouterConfig>({ ...config });

  useEffect(() => { setForm({ ...config }); }, [config]);

  // Group models by provider
  const providers = useMemo(() => {
    if (!available) return {} as Record<string, AvailableModel[]>;
    return available as Record<string, AvailableModel[]>;
  }, [available]);

  const providerNames = useMemo(() => Object.keys(providers), [providers]);

  const handleSave = async () => {
    await updateRouter.mutateAsync(form);
    toast.success('Model router settings saved');
  };

  const tierCards = [
    { key: 'smart', label: 'Smart', color: 'success', modelField: 'smartModel' as const, reasoningField: 'smartModelReasoning' as const },
    { key: 'coding', label: 'Coding', color: 'info', modelField: 'codingModel' as const, reasoningField: 'codingModelReasoning' as const },
    { key: 'deep', label: 'Deep', color: 'warning', modelField: 'deepModel' as const, reasoningField: 'deepModelReasoning' as const },
  ];

  return (
    <>
      <Card className="settings-card mb-3">
        <Card.Body>
          <Card.Title className="h6 mb-3">Global Settings</Card.Title>
          <Row className="g-3">
            <Col md={6}>
              <Form.Group>
                <Form.Label className="small fw-medium">
                  Temperature: {form.temperature?.toFixed(1) ?? '0.7'}
                  <Tip text="Controls randomness of LLM responses. Higher = more creative, lower = more deterministic. Ignored by reasoning models." />
                </Form.Label>
                <Form.Range min={0} max={2} step={0.1} value={form.temperature ?? 0.7}
                  onChange={(e) => setForm({ ...form, temperature: parseFloat(e.target.value) })} />
              </Form.Group>
            </Col>
            <Col md={6} className="d-flex align-items-end">
              <Form.Check type="switch" label={<>Dynamic tier upgrade <Tip text="Automatically upgrade to coding tier when code-related activity is detected mid-conversation" /></>}
                checked={form.dynamicTierEnabled ?? true}
                onChange={(e) => setForm({ ...form, dynamicTierEnabled: e.target.checked })} />
            </Col>
          </Row>
        </Card.Body>
      </Card>

      <Row className="g-3 mb-3">
        <Col md={6} lg={3}>
          <TierModelCard
            label="Routing"
            color="primary"
            providers={providers}
            providerNames={providerNames}
            modelValue={form.balancedModel ?? ''}
            reasoningValue={form.balancedModelReasoning ?? ''}
            onModelChange={(val) => setForm({ ...form, balancedModel: val || null, balancedModelReasoning: null })}
            onReasoningChange={(val) => setForm({ ...form, balancedModelReasoning: val || null })}
          />
        </Col>
        {tierCards.map(({ key, label, color, modelField, reasoningField }) => (
          <Col md={6} lg={3} key={key}>
            <TierModelCard
              label={label}
              color={color}
              providers={providers}
              providerNames={providerNames}
              modelValue={form[modelField] ?? ''}
              reasoningValue={form[reasoningField] ?? ''}
              onModelChange={(val) => setForm({ ...form, [modelField]: val || null, [reasoningField]: null })}
              onReasoningChange={(val) => setForm({ ...form, [reasoningField]: val || null })}
            />
          </Col>
        ))}
      </Row>

      <Button variant="primary" size="sm" onClick={handleSave}>Save Model Configuration</Button>
    </>
  );
}

function TierModelCard({ label, color, providers, providerNames, modelValue, reasoningValue, onModelChange, onReasoningChange }: {
  label: string;
  color: string;
  providers: Record<string, AvailableModel[]>;
  providerNames: string[];
  modelValue: string;
  reasoningValue: string;
  onModelChange: (v: string) => void;
  onReasoningChange: (v: string) => void;
}) {
  // Determine selected provider from current model
  const selectedProvider = useMemo(() => {
    if (!modelValue) return providerNames[0] ?? '';
    for (const [prov, models] of Object.entries(providers)) {
      if (models.some((m) => m.id === modelValue)) return prov;
    }
    return providerNames[0] ?? '';
  }, [modelValue, providers, providerNames]);

  const [provider, setProvider] = useState(selectedProvider);

  useEffect(() => { setProvider(selectedProvider); }, [selectedProvider]);

  const modelsForProvider = providers[provider] ?? [];
  const selectedModel = modelsForProvider.find((m) => m.id === modelValue);
  const reasoningLevels = selectedModel?.reasoningLevels ?? [];

  return (
    <Card className="tier-card h-100">
      <Card.Body className="p-3">
        <Badge bg={color} className="mb-2">{label}</Badge>

        <Form.Group className="mb-2">
          <Form.Label className="small fw-medium mb-1">Provider</Form.Label>
          <Form.Select size="sm" value={provider}
            onChange={(e) => { setProvider(e.target.value); onModelChange(''); }}>
            {providerNames.map((p) => (
              <option key={p} value={p}>{p}</option>
            ))}
          </Form.Select>
        </Form.Group>

        <Form.Group className="mb-2">
          <Form.Label className="small fw-medium mb-1">Model</Form.Label>
          <Form.Select size="sm" value={modelValue}
            onChange={(e) => onModelChange(e.target.value)}>
            <option value="">Default</option>
            {modelsForProvider.map((m) => (
              <option key={m.id} value={m.id}>{m.displayName || m.id}</option>
            ))}
          </Form.Select>
        </Form.Group>

        {reasoningLevels.length > 0 && (
          <Form.Group>
            <Form.Label className="small fw-medium mb-1">Reasoning</Form.Label>
            <Form.Select size="sm" value={reasoningValue}
              onChange={(e) => onReasoningChange(e.target.value)}>
              <option value="">Default</option>
              {reasoningLevels.map((l) => (
                <option key={l} value={l}>{l}</option>
              ))}
            </Form.Select>
          </Form.Group>
        )}
      </Card.Body>
    </Card>
  );
}

// ==================== Tools Tab ====================

function ToolsTab({ config }: { config: ToolsConfig }) {
  const updateTools = useUpdateTools();
  const [form, setForm] = useState<ToolsConfig>({ ...config });

  useEffect(() => { setForm({ ...config }); }, [config]);

  const tools = [
    { key: 'filesystemEnabled' as const, label: 'Filesystem', desc: 'Read/write files in sandbox', tip: 'Allows the bot to create, read, and modify files in the sandboxed workspace directory' },
    { key: 'shellEnabled' as const, label: 'Shell', desc: 'Execute shell commands', tip: 'Allows the bot to run shell commands (ls, grep, python, etc.) in the sandboxed workspace' },
    { key: 'braveSearchEnabled' as const, label: 'Brave Search', desc: 'Web search via Brave API', tip: 'Enables web search using Brave Search API. Requires a valid API key (free tier: 2000 queries/month)' },
    { key: 'skillManagementEnabled' as const, label: 'Skill Management', desc: 'Create/edit skills', tip: 'Allows the LLM to create, list, and delete skill definitions programmatically' },
    { key: 'skillTransitionEnabled' as const, label: 'Skill Transition', desc: 'LLM-initiated skill switching', tip: 'Allows the LLM to transition between skills during a conversation pipeline' },
    { key: 'tierEnabled' as const, label: 'Tier Tool', desc: 'LLM-initiated tier switching', tip: 'Allows the LLM to upgrade/downgrade model tier within a session' },
    { key: 'goalManagementEnabled' as const, label: 'Goal Management', desc: 'Auto mode goal management', tip: 'Allows the LLM to create, update, and complete goals in autonomous mode' },
  ];

  const handleSave = async () => {
    await updateTools.mutateAsync(form);
    toast.success('Tools settings saved');
  };

  const updateImap = (partial: Partial<ImapConfig>) => {
    setForm({ ...form, imap: { ...form.imap, ...partial } });
  };

  const updateSmtp = (partial: Partial<SmtpConfig>) => {
    setForm({ ...form, smtp: { ...form.smtp, ...partial } });
  };

  return (
    <>
      <Card className="settings-card mb-3">
        <Card.Body>
          <Card.Title className="h6 mb-3">Tool Toggles</Card.Title>
          <div className="mb-3">
            {tools.map(({ key, label, desc, tip }) => (
              <div key={key} className="d-flex align-items-start py-2 border-bottom">
                <Form.Check type="switch"
                  checked={form[key] ?? true}
                  onChange={(e) => setForm({ ...form, [key]: e.target.checked })}
                  className="me-3"
                />
                <div>
                  <div className="fw-medium small">{label} <Tip text={tip} /></div>
                  <div className="text-muted" style={{ fontSize: '0.8rem' }}>{desc}</div>
                </div>
              </div>
            ))}
          </div>
          {form.braveSearchEnabled && (
            <Form.Group className="mb-3">
              <Form.Label className="small fw-medium">
                Brave Search API Key <Tip text="Get your free API key at brave.com/search/api" />
              </Form.Label>
              <Form.Control size="sm" type="password" value={form.braveSearchApiKey ?? ''}
                onChange={(e) => setForm({ ...form, braveSearchApiKey: e.target.value || null })}
                placeholder="BSA-..." />
            </Form.Group>
          )}
          <Button variant="primary" size="sm" onClick={handleSave}>Save</Button>
        </Card.Body>
      </Card>

      {/* IMAP Settings */}
      <Card className="settings-card mb-3">
        <Card.Body>
          <Card.Title className="h6 mb-3">
            IMAP (Email Reading) <Tip text="Read emails from an IMAP mailbox. The bot can search, read, and list emails." />
          </Card.Title>
          <Form.Check type="switch" label="Enable IMAP" checked={form.imap?.enabled ?? false}
            onChange={(e) => updateImap({ enabled: e.target.checked })} className="mb-3" />
          {form.imap?.enabled && (
            <>
              <Row className="g-3 mb-3">
                <Col md={6}>
                  <Form.Group>
                    <Form.Label className="small fw-medium">
                      Host <Tip text="IMAP server hostname (e.g. imap.gmail.com)" />
                    </Form.Label>
                    <Form.Control size="sm" value={form.imap?.host ?? ''}
                      onChange={(e) => updateImap({ host: e.target.value || null })}
                      placeholder="imap.gmail.com" />
                  </Form.Group>
                </Col>
                <Col md={3}>
                  <Form.Group>
                    <Form.Label className="small fw-medium">
                      Port <Tip text="IMAP port (993 for SSL, 143 for plain/STARTTLS)" />
                    </Form.Label>
                    <Form.Control size="sm" type="number" value={form.imap?.port ?? 993}
                      onChange={(e) => updateImap({ port: parseInt(e.target.value) || null })} />
                  </Form.Group>
                </Col>
                <Col md={3}>
                  <Form.Group>
                    <Form.Label className="small fw-medium">
                      Security <Tip text="Connection security: ssl (port 993), starttls (port 143), or none" />
                    </Form.Label>
                    <Form.Select size="sm" value={form.imap?.security ?? 'ssl'}
                      onChange={(e) => updateImap({ security: e.target.value })}>
                      <option value="ssl">SSL</option>
                      <option value="starttls">STARTTLS</option>
                      <option value="none">None</option>
                    </Form.Select>
                  </Form.Group>
                </Col>
              </Row>
              <Row className="g-3 mb-3">
                <Col md={6}>
                  <Form.Group>
                    <Form.Label className="small fw-medium">Username</Form.Label>
                    <Form.Control size="sm" value={form.imap?.username ?? ''}
                      onChange={(e) => updateImap({ username: e.target.value || null })}
                      placeholder="user@example.com" />
                  </Form.Group>
                </Col>
                <Col md={6}>
                  <Form.Group>
                    <Form.Label className="small fw-medium">
                      Password <Tip text="For Gmail, use an App Password (not your regular password)" />
                    </Form.Label>
                    <Form.Control size="sm" type="password" value={form.imap?.password ?? ''}
                      onChange={(e) => updateImap({ password: e.target.value || null })} />
                  </Form.Group>
                </Col>
              </Row>
              <Row className="g-3">
                <Col md={4}>
                  <Form.Group>
                    <Form.Label className="small fw-medium">
                      Max Body Length <Tip text="Maximum number of characters to read from email body" />
                    </Form.Label>
                    <Form.Control size="sm" type="number" value={form.imap?.maxBodyLength ?? 50000}
                      onChange={(e) => updateImap({ maxBodyLength: parseInt(e.target.value) || null })} />
                  </Form.Group>
                </Col>
                <Col md={4}>
                  <Form.Group>
                    <Form.Label className="small fw-medium">
                      Default Message Limit <Tip text="Max emails returned per listing request" />
                    </Form.Label>
                    <Form.Control size="sm" type="number" value={form.imap?.defaultMessageLimit ?? 20}
                      onChange={(e) => updateImap({ defaultMessageLimit: parseInt(e.target.value) || null })} />
                  </Form.Group>
                </Col>
                <Col md={4}>
                  <Form.Group>
                    <Form.Label className="small fw-medium">
                      SSL Trust <Tip text="SSL certificate trust configuration. Leave blank for default, or set to '*' to trust all." />
                    </Form.Label>
                    <Form.Control size="sm" value={form.imap?.sslTrust ?? ''}
                      onChange={(e) => updateImap({ sslTrust: e.target.value || null })}
                      placeholder="*" />
                  </Form.Group>
                </Col>
              </Row>
            </>
          )}
        </Card.Body>
      </Card>

      {/* SMTP Settings */}
      <Card className="settings-card mb-3">
        <Card.Body>
          <Card.Title className="h6 mb-3">
            SMTP (Email Sending) <Tip text="Send emails via SMTP. The bot can compose and send emails on your behalf." />
          </Card.Title>
          <Form.Check type="switch" label="Enable SMTP" checked={form.smtp?.enabled ?? false}
            onChange={(e) => updateSmtp({ enabled: e.target.checked })} className="mb-3" />
          {form.smtp?.enabled && (
            <>
              <Row className="g-3 mb-3">
                <Col md={6}>
                  <Form.Group>
                    <Form.Label className="small fw-medium">
                      Host <Tip text="SMTP server hostname (e.g. smtp.gmail.com)" />
                    </Form.Label>
                    <Form.Control size="sm" value={form.smtp?.host ?? ''}
                      onChange={(e) => updateSmtp({ host: e.target.value || null })}
                      placeholder="smtp.gmail.com" />
                  </Form.Group>
                </Col>
                <Col md={3}>
                  <Form.Group>
                    <Form.Label className="small fw-medium">
                      Port <Tip text="SMTP port (587 for STARTTLS, 465 for SSL, 25 for plain)" />
                    </Form.Label>
                    <Form.Control size="sm" type="number" value={form.smtp?.port ?? 587}
                      onChange={(e) => updateSmtp({ port: parseInt(e.target.value) || null })} />
                  </Form.Group>
                </Col>
                <Col md={3}>
                  <Form.Group>
                    <Form.Label className="small fw-medium">
                      Security <Tip text="Connection security: starttls (port 587), ssl (port 465), or none" />
                    </Form.Label>
                    <Form.Select size="sm" value={form.smtp?.security ?? 'starttls'}
                      onChange={(e) => updateSmtp({ security: e.target.value })}>
                      <option value="starttls">STARTTLS</option>
                      <option value="ssl">SSL</option>
                      <option value="none">None</option>
                    </Form.Select>
                  </Form.Group>
                </Col>
              </Row>
              <Row className="g-3 mb-3">
                <Col md={6}>
                  <Form.Group>
                    <Form.Label className="small fw-medium">Username</Form.Label>
                    <Form.Control size="sm" value={form.smtp?.username ?? ''}
                      onChange={(e) => updateSmtp({ username: e.target.value || null })}
                      placeholder="user@example.com" />
                  </Form.Group>
                </Col>
                <Col md={6}>
                  <Form.Group>
                    <Form.Label className="small fw-medium">
                      Password <Tip text="For Gmail, use an App Password (not your regular password)" />
                    </Form.Label>
                    <Form.Control size="sm" type="password" value={form.smtp?.password ?? ''}
                      onChange={(e) => updateSmtp({ password: e.target.value || null })} />
                  </Form.Group>
                </Col>
              </Row>
              <Row className="g-3">
                <Col md={6}>
                  <Form.Group>
                    <Form.Label className="small fw-medium">
                      SSL Trust <Tip text="SSL certificate trust configuration. Leave blank for default." />
                    </Form.Label>
                    <Form.Control size="sm" value={form.smtp?.sslTrust ?? ''}
                      onChange={(e) => updateSmtp({ sslTrust: e.target.value || null })}
                      placeholder="*" />
                  </Form.Group>
                </Col>
              </Row>
            </>
          )}
        </Card.Body>
      </Card>

      <Button variant="primary" size="sm" onClick={handleSave}>Save All Tool Settings</Button>
    </>
  );
}

// ==================== Voice Tab ====================

function VoiceTab({ config }: { config: VoiceConfig }) {
  const updateVoice = useUpdateVoice();
  const [form, setForm] = useState<VoiceConfig>({ ...config });
  const [showKey, setShowKey] = useState(false);

  useEffect(() => { setForm({ ...config }); }, [config]);

  const handleSave = async () => {
    await updateVoice.mutateAsync(form);
    toast.success('Voice settings saved');
  };

  return (
    <Card className="settings-card">
      <Card.Body>
        <Card.Title className="h6 mb-3">Voice (ElevenLabs)</Card.Title>
        <Form.Check type="switch" label={<>Enable Voice <Tip text="Enable speech-to-text and text-to-speech via ElevenLabs API" /></>}
          checked={form.enabled ?? false}
          onChange={(e) => setForm({ ...form, enabled: e.target.checked })} className="mb-3" />

        <Form.Group className="mb-3">
          <Form.Label className="small fw-medium">
            API Key <Tip text="Your ElevenLabs API key from elevenlabs.io/app/settings/api-keys" />
          </Form.Label>
          <InputGroup size="sm">
            <Form.Control type={showKey ? 'text' : 'password'} value={form.apiKey ?? ''}
              onChange={(e) => setForm({ ...form, apiKey: e.target.value || null })} />
            <Button variant="secondary" onClick={() => setShowKey(!showKey)}>
              {showKey ? 'Hide' : 'Show'}
            </Button>
          </InputGroup>
        </Form.Group>

        <Row className="g-3">
          <Col md={6}>
            <Form.Group>
              <Form.Label className="small fw-medium">
                Voice ID <Tip text="ElevenLabs voice identifier. Find voices at elevenlabs.io/voice-library" />
              </Form.Label>
              <Form.Control size="sm" value={form.voiceId ?? ''}
                onChange={(e) => setForm({ ...form, voiceId: e.target.value || null })} />
            </Form.Group>
          </Col>
          <Col md={6}>
            <Form.Group>
              <Form.Label className="small fw-medium">
                Speed: {form.speed?.toFixed(1) ?? '1.0'}
                <Tip text="Voice playback speed multiplier (0.5 = half speed, 2.0 = double speed)" />
              </Form.Label>
              <Form.Range min={0.5} max={2.0} step={0.1} value={form.speed ?? 1.0}
                onChange={(e) => setForm({ ...form, speed: parseFloat(e.target.value) })} />
            </Form.Group>
          </Col>
        </Row>

        <Row className="g-3 mt-1">
          <Col md={6}>
            <Form.Group>
              <Form.Label className="small fw-medium">
                TTS Model <Tip text="Text-to-speech model. eleven_multilingual_v2 supports 29 languages." />
              </Form.Label>
              <Form.Control size="sm" value={form.ttsModelId ?? ''}
                onChange={(e) => setForm({ ...form, ttsModelId: e.target.value || null })}
                placeholder="eleven_multilingual_v2" />
            </Form.Group>
          </Col>
          <Col md={6}>
            <Form.Group>
              <Form.Label className="small fw-medium">
                STT Model <Tip text="Speech-to-text model for transcribing voice messages." />
              </Form.Label>
              <Form.Control size="sm" value={form.sttModelId ?? ''}
                onChange={(e) => setForm({ ...form, sttModelId: e.target.value || null })}
                placeholder="scribe_v1" />
            </Form.Group>
          </Col>
        </Row>

        <div className="mt-3">
          <Button variant="primary" size="sm" onClick={handleSave}>Save</Button>
        </div>
      </Card.Body>
    </Card>
  );
}

// ==================== Webhooks Tab ====================

function generateSecureToken(): string {
  const array = new Uint8Array(32);
  crypto.getRandomValues(array);
  return Array.from(array, b => b.toString(16).padStart(2, '0')).join('');
}

function WebhooksTab() {
  const { data: settings } = useSettings();
  const updateWebhooks = useUpdateWebhooks();

  const webhookConfig: WebhookConfig = settings?.webhooks ?? {
    enabled: false, token: null, maxPayloadSize: 65536,
    defaultTimeoutSeconds: 300, mappings: [],
  };

  const [form, setForm] = useState<WebhookConfig>(webhookConfig);
  const [editIdx, setEditIdx] = useState<number | null>(null);

  useEffect(() => {
    if (settings?.webhooks) setForm(settings.webhooks);
  }, [settings]);

  const handleSave = async () => {
    await updateWebhooks.mutateAsync(form);
    toast.success('Webhook settings saved');
  };

  const handleGenerateToken = () => {
    const token = generateSecureToken();
    setForm({ ...form, token });
    toast.success('Token generated');
  };

  const addMapping = () => {
    const newMapping: HookMapping = {
      name: '', action: 'wake', authMode: 'bearer',
      hmacHeader: null, hmacSecret: null, hmacPrefix: null,
      messageTemplate: null, model: null,
      deliver: false, channel: null, to: null,
    };
    setForm({ ...form, mappings: [...form.mappings, newMapping] });
    setEditIdx(form.mappings.length);
  };

  const removeMapping = (idx: number) => {
    const mappings = form.mappings.filter((_, i) => i !== idx);
    setForm({ ...form, mappings });
    if (editIdx === idx) setEditIdx(null);
  };

  const updateMapping = (idx: number, partial: Partial<HookMapping>) => {
    const mappings = form.mappings.map((m, i) => i === idx ? { ...m, ...partial } : m);
    setForm({ ...form, mappings });
  };

  return (
    <Card className="settings-card">
      <Card.Body>
        <Card.Title className="h6 mb-3">
          Webhooks <Tip text="Inbound HTTP webhooks allow external services to trigger bot actions" />
        </Card.Title>
        <Form.Check type="switch" label="Enable Webhooks" checked={form.enabled}
          onChange={(e) => setForm({ ...form, enabled: e.target.checked })} className="mb-3" />

        <Row className="g-3 mb-3">
          <Col md={6}>
            <Form.Group>
              <Form.Label className="small fw-medium">
                Bearer Token <Tip text="Secret token for authenticating incoming webhook requests" />
              </Form.Label>
              <InputGroup size="sm">
                <Form.Control type="password" value={form.token ?? ''}
                  onChange={(e) => setForm({ ...form, token: e.target.value || null })} />
                <Button variant="secondary" onClick={handleGenerateToken} title="Generate random token">
                  Generate
                </Button>
              </InputGroup>
            </Form.Group>
          </Col>
          <Col md={6}>
            <Form.Group>
              <Form.Label className="small fw-medium">
                Max Payload Size <Tip text="Maximum allowed webhook request body size in bytes" />
              </Form.Label>
              <Form.Control size="sm" type="number" value={form.maxPayloadSize}
                onChange={(e) => setForm({ ...form, maxPayloadSize: parseInt(e.target.value) || 65536 })} />
            </Form.Group>
          </Col>
          <Col md={4}>
            <Form.Group>
              <Form.Label className="small fw-medium">
                Default Timeout (s) <Tip text="Maximum time in seconds for the bot to process an agent webhook request" />
              </Form.Label>
              <Form.Control size="sm" type="number" value={form.defaultTimeoutSeconds}
                onChange={(e) => setForm({ ...form, defaultTimeoutSeconds: parseInt(e.target.value) || 300 })} />
            </Form.Group>
          </Col>
        </Row>

        <div className="d-flex align-items-center justify-content-between mb-2">
          <span className="small fw-medium">Hook Mappings <Tip text="Named endpoints (/api/hooks/{name}) that map incoming webhooks to bot actions" /></span>
          <Button variant="primary" size="sm" onClick={addMapping}>Add Webhook</Button>
        </div>

        {form.mappings.length > 0 ? (
          <Table size="sm" hover className="mb-3">
            <thead><tr><th>Name</th><th>Action</th><th>Auth</th><th></th></tr></thead>
            <tbody>
              {form.mappings.map((m, idx) => (
                <tr key={idx}>
                  <td>{m.name || <em className="text-muted">unnamed</em>}</td>
                  <td><Badge bg={m.action === 'agent' ? 'primary' : 'secondary'}>{m.action}</Badge></td>
                  <td className="small">{m.authMode}</td>
                  <td className="text-end">
                    <Button size="sm" variant="secondary" className="me-2"
                      onClick={() => setEditIdx(editIdx === idx ? null : idx)}>
                      {editIdx === idx ? 'Close' : 'Edit'}
                    </Button>
                    <Button size="sm" variant="danger" onClick={() => removeMapping(idx)}>Delete</Button>
                  </td>
                </tr>
              ))}
            </tbody>
          </Table>
        ) : (
          <p className="text-muted small">No webhook mappings configured</p>
        )}

        {editIdx !== null && form.mappings[editIdx] && (
          <Card className="mb-3 border-primary">
            <Card.Body className="p-3">
              <Row className="g-2">
                <Col md={4}>
                  <Form.Group>
                    <Form.Label className="small fw-medium">
                      Name <Tip text="URL path segment: /api/hooks/{name}" />
                    </Form.Label>
                    <Form.Control size="sm" value={form.mappings[editIdx].name}
                      onChange={(e) => updateMapping(editIdx, { name: e.target.value })} />
                  </Form.Group>
                </Col>
                <Col md={4}>
                  <Form.Group>
                    <Form.Label className="small fw-medium">
                      Action <Tip text="'wake' sends a fire-and-forget message; 'agent' runs a full agent turn and waits for response" />
                    </Form.Label>
                    <Form.Select size="sm" value={form.mappings[editIdx].action}
                      onChange={(e) => updateMapping(editIdx, { action: e.target.value })}>
                      <option value="wake">Wake</option>
                      <option value="agent">Agent</option>
                    </Form.Select>
                  </Form.Group>
                </Col>
                <Col md={4}>
                  <Form.Group>
                    <Form.Label className="small fw-medium">
                      Auth Mode <Tip text="'bearer' uses Authorization header; 'hmac' uses HMAC-SHA256 signature verification" />
                    </Form.Label>
                    <Form.Select size="sm" value={form.mappings[editIdx].authMode}
                      onChange={(e) => updateMapping(editIdx, { authMode: e.target.value })}>
                      <option value="bearer">Bearer</option>
                      <option value="hmac">HMAC</option>
                    </Form.Select>
                  </Form.Group>
                </Col>
                <Col md={12}>
                  <Form.Group>
                    <Form.Label className="small fw-medium">
                      Message Template <Tip text="Template for the message sent to the bot. Use {field.path} placeholders to extract values from webhook JSON payload." />
                    </Form.Label>
                    <Form.Control size="sm" as="textarea" rows={2}
                      value={form.mappings[editIdx].messageTemplate ?? ''}
                      onChange={(e) => updateMapping(editIdx, { messageTemplate: e.target.value || null })}
                      placeholder="e.g. New {action.type} event: {repository.full_name}" />
                  </Form.Group>
                </Col>
                <Col md={4}>
                  <Form.Check type="switch" label={<>Deliver to channel <Tip text="Forward the bot response to a messaging channel (e.g. Telegram)" /></>}
                    className="mt-2"
                    checked={form.mappings[editIdx].deliver}
                    onChange={(e) => updateMapping(editIdx, { deliver: e.target.checked })} />
                </Col>
                {form.mappings[editIdx].deliver && (
                  <>
                    <Col md={4}>
                      <Form.Group>
                        <Form.Label className="small fw-medium">Channel</Form.Label>
                        <Form.Control size="sm" value={form.mappings[editIdx].channel ?? ''}
                          onChange={(e) => updateMapping(editIdx, { channel: e.target.value || null })} placeholder="telegram" />
                      </Form.Group>
                    </Col>
                    <Col md={4}>
                      <Form.Group>
                        <Form.Label className="small fw-medium">To (Chat ID)</Form.Label>
                        <Form.Control size="sm" value={form.mappings[editIdx].to ?? ''}
                          onChange={(e) => updateMapping(editIdx, { to: e.target.value || null })} />
                      </Form.Group>
                    </Col>
                  </>
                )}
              </Row>
            </Card.Body>
          </Card>
        )}

        <Button variant="primary" size="sm" onClick={handleSave}>Save</Button>
      </Card.Body>
    </Card>
  );
}

// ==================== Auto Mode Tab ====================

function AutoModeTab({ config }: { config: AutoModeConfig }) {
  const updateAuto = useUpdateAuto();
  const [form, setForm] = useState<AutoModeConfig>({ ...config });

  useEffect(() => { setForm({ ...config }); }, [config]);

  const handleSave = async () => {
    await updateAuto.mutateAsync({ ...form, tickIntervalSeconds: 1 });
    toast.success('Auto mode settings saved');
  };

  return (
    <Card className="settings-card">
      <Card.Body>
        <Card.Title className="h6 mb-3">
          Auto Mode <Tip text="Autonomous mode where the bot works on goals independently, checking in periodically" />
        </Card.Title>
        <Form.Check type="switch" label="Enable Auto Mode" checked={form.enabled ?? false}
          onChange={(e) => setForm({ ...form, enabled: e.target.checked })} className="mb-3" />

        <Row className="g-3 mb-3">
          <Col md={4}>
            <Form.Group>
              <Form.Label className="small fw-medium">
                Task Time Limit (minutes) <Tip text="Maximum time a single autonomous task can run before being stopped" />
              </Form.Label>
              <Form.Control size="sm" type="number" value={form.taskTimeLimitMinutes ?? 10}
                onChange={(e) => setForm({ ...form, taskTimeLimitMinutes: parseInt(e.target.value) || null })} />
            </Form.Group>
          </Col>
          <Col md={4}>
            <Form.Group>
              <Form.Label className="small fw-medium">
                Max Goals <Tip text="Maximum number of concurrent goals the bot can work on" />
              </Form.Label>
              <Form.Control size="sm" type="number" value={form.maxGoals ?? 3}
                onChange={(e) => setForm({ ...form, maxGoals: parseInt(e.target.value) || null })} />
            </Form.Group>
          </Col>
        </Row>

        <Form.Group className="mb-3">
          <Form.Label className="small fw-medium">
            Model Tier <Tip text="Which model tier to use for autonomous tasks" />
          </Form.Label>
          <Form.Select size="sm" value={form.modelTier ?? 'default'}
            onChange={(e) => setForm({ ...form, modelTier: e.target.value })}>
            <option value="default">Default</option>
            <option value="balanced">Balanced</option>
            <option value="smart">Smart</option>
            <option value="coding">Coding</option>
            <option value="deep">Deep</option>
          </Form.Select>
        </Form.Group>

        <Form.Check type="switch"
          label={<>Auto-start on startup <Tip text="Start autonomous mode automatically when the application boots" /></>}
          checked={form.autoStart ?? true}
          onChange={(e) => setForm({ ...form, autoStart: e.target.checked })} className="mb-2" />
        <Form.Check type="switch"
          label={<>Notify milestones <Tip text="Send notifications when goals or tasks are completed" /></>}
          checked={form.notifyMilestones ?? true}
          onChange={(e) => setForm({ ...form, notifyMilestones: e.target.checked })} className="mb-3" />

        <Button variant="primary" size="sm" onClick={handleSave}>Save</Button>
      </Card.Body>
    </Card>
  );
}

// ==================== Advanced Tab ====================

function AdvancedTab({ rateLimit, security, compaction }: {
  rateLimit: RateLimitConfig; security: SecurityConfig; compaction: CompactionConfig;
}) {
  const updateAdvanced = useUpdateAdvanced();
  const [rl, setRl] = useState<RateLimitConfig>({ ...rateLimit });
  const [sec, setSec] = useState<SecurityConfig>({ ...security });
  const [comp, setComp] = useState<CompactionConfig>({ ...compaction });

  useEffect(() => { setRl({ ...rateLimit }); }, [rateLimit]);
  useEffect(() => { setSec({ ...security }); }, [security]);
  useEffect(() => { setComp({ ...compaction }); }, [compaction]);

  const handleSave = async () => {
    await updateAdvanced.mutateAsync({ rateLimit: rl, security: sec, compaction: comp });
    toast.success('Advanced settings saved');
  };

  return (
    <>
      <Row className="g-3 mb-3">
        <Col lg={4}>
          <Card className="settings-card h-100">
            <Card.Body>
              <Card.Title className="h6 mb-3">
                Rate Limiting <Tip text="Throttle user requests to prevent abuse and manage API costs" />
              </Card.Title>
              <Form.Check type="switch" label="Enable" checked={rl.enabled ?? true}
                onChange={(e) => setRl({ ...rl, enabled: e.target.checked })} className="mb-3" />
              <Form.Group className="mb-2">
                <Form.Label className="small fw-medium">
                  Requests/minute <Tip text="Maximum LLM requests per user per minute" />
                </Form.Label>
                <Form.Control size="sm" type="number" value={rl.userRequestsPerMinute ?? 20}
                  onChange={(e) => setRl({ ...rl, userRequestsPerMinute: parseInt(e.target.value) || null })} />
              </Form.Group>
              <Form.Group className="mb-2">
                <Form.Label className="small fw-medium">
                  Requests/hour <Tip text="Maximum LLM requests per user per hour" />
                </Form.Label>
                <Form.Control size="sm" type="number" value={rl.userRequestsPerHour ?? 100}
                  onChange={(e) => setRl({ ...rl, userRequestsPerHour: parseInt(e.target.value) || null })} />
              </Form.Group>
              <Form.Group>
                <Form.Label className="small fw-medium">
                  Requests/day <Tip text="Maximum LLM requests per user per day" />
                </Form.Label>
                <Form.Control size="sm" type="number" value={rl.userRequestsPerDay ?? 500}
                  onChange={(e) => setRl({ ...rl, userRequestsPerDay: parseInt(e.target.value) || null })} />
              </Form.Group>
            </Card.Body>
          </Card>
        </Col>

        <Col lg={4}>
          <Card className="settings-card h-100">
            <Card.Body>
              <Card.Title className="h6 mb-3">
                Context Compaction <Tip text="Automatically compress conversation history when it approaches the token limit" />
              </Card.Title>
              <Form.Check type="switch" label="Enable" checked={comp.enabled ?? true}
                onChange={(e) => setComp({ ...comp, enabled: e.target.checked })} className="mb-3" />
              <Form.Group className="mb-2">
                <Form.Label className="small fw-medium">
                  Max Context Tokens <Tip text="Token threshold that triggers automatic context compaction" />
                </Form.Label>
                <Form.Control size="sm" type="number" value={comp.maxContextTokens ?? 50000}
                  onChange={(e) => setComp({ ...comp, maxContextTokens: parseInt(e.target.value) || null })} />
              </Form.Group>
              <Form.Group>
                <Form.Label className="small fw-medium">
                  Keep Last Messages <Tip text="Number of recent messages to preserve during compaction" />
                </Form.Label>
                <Form.Control size="sm" type="number" value={comp.keepLastMessages ?? 20}
                  onChange={(e) => setComp({ ...comp, keepLastMessages: parseInt(e.target.value) || null })} />
              </Form.Group>
            </Card.Body>
          </Card>
        </Col>

        <Col lg={4}>
          <Card className="settings-card h-100">
            <Card.Body>
              <Card.Title className="h6 mb-3">
                Security <Tip text="Input validation and injection detection for incoming messages" />
              </Card.Title>
              <Form.Check type="switch"
                label={<>Sanitize input <Tip text="Strip potentially dangerous characters and HTML from user messages" /></>}
                checked={sec.sanitizeInput ?? true}
                onChange={(e) => setSec({ ...sec, sanitizeInput: e.target.checked })} className="mb-2" />
              <Form.Check type="switch"
                label={<>Detect prompt injection <Tip text="Detect and block attempts to override the system prompt" /></>}
                checked={sec.detectPromptInjection ?? true}
                onChange={(e) => setSec({ ...sec, detectPromptInjection: e.target.checked })} className="mb-2" />
              <Form.Check type="switch"
                label={<>Detect command injection <Tip text="Detect and block shell command injection attempts in tool parameters" /></>}
                checked={sec.detectCommandInjection ?? true}
                onChange={(e) => setSec({ ...sec, detectCommandInjection: e.target.checked })} className="mb-3" />
              <Form.Group>
                <Form.Label className="small fw-medium">
                  Max Input Length <Tip text="Maximum allowed characters per user message" />
                </Form.Label>
                <Form.Control size="sm" type="number" value={sec.maxInputLength ?? 10000}
                  onChange={(e) => setSec({ ...sec, maxInputLength: parseInt(e.target.value) || null })} />
              </Form.Group>
            </Card.Body>
          </Card>
        </Col>
      </Row>

      <Button variant="primary" size="sm" onClick={handleSave}>Save All</Button>
    </>
  );
}
