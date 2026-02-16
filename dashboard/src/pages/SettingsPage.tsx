import { useState, useEffect, useMemo } from 'react';
import {
  Card, Form, Button, Row, Col, Spinner,
  Table, Badge, InputGroup, OverlayTrigger, Tooltip, Placeholder,
} from 'react-bootstrap';
import { useNavigate, useParams } from 'react-router-dom';
import {
  useSettings, useUpdatePreferences, useRuntimeConfig,
  useUpdateTelegram, useUpdateModelRouter, useUpdateTools,
  useUpdateLlm,
  useUpdateVoice, useUpdateMemory, useUpdateSkills,
  useUpdateTurn,
  useUpdateWebhooks, useUpdateAuto, useUpdateAdvanced,
  useUpdateUsage,
  useUpdateRag,
  useUpdateMcp,
  useGenerateInviteCode, useDeleteInviteCode, useRestartTelegram,
} from '../hooks/useSettings';
import { useAvailableModels } from '../hooks/useModels';
import { useMe } from '../hooks/useAuth';
import { changePassword } from '../api/auth';
import MfaSetup from '../components/auth/MfaSetup';
import toast from 'react-hot-toast';
import { useQueryClient } from '@tanstack/react-query';
import type {
  TelegramConfig, ModelRouterConfig, LlmConfig, ToolsConfig, VoiceConfig,
  MemoryConfig, SkillsConfig,
  TurnConfig,
  UsageConfig,
  RagConfig,
  McpConfig,
  AutoModeConfig, RateLimitConfig, SecurityConfig, CompactionConfig,
  WebhookConfig, HookMapping, ImapConfig, SmtpConfig,
} from '../api/settings';
import {
  FiHelpCircle, FiSliders, FiSend, FiCpu, FiTool, FiMic,
  FiGlobe, FiPlayCircle, FiShield, FiSearch, FiHardDrive, FiBarChart2,
  FiTerminal, FiMail, FiCompass, FiShuffle, FiKey,
} from 'react-icons/fi';
import ConfirmModal from '../components/common/ConfirmModal';
import { useBrowserHealthPing } from '../hooks/useSystem';

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

function hasDiff<T>(current: T, initial: T): boolean {
  return JSON.stringify(current) !== JSON.stringify(initial);
}

function SaveStateHint({ isDirty }: { isDirty: boolean }) {
  return (
    <small className="text-body-secondary">
      {isDirty ? 'Unsaved changes' : 'All changes saved'}
    </small>
  );
}

// ==================== Main ====================

const SETTINGS_SECTIONS = [
  { key: 'general', title: 'General', description: 'Preferences, account security, and MFA', icon: <FiSliders size={18} /> },
  { key: 'telegram', title: 'Telegram', description: 'Bot token, auth mode, and invite codes', icon: <FiSend size={18} /> },
  { key: 'models', title: 'Model Router', description: 'Routing and tier model configuration', icon: <FiCpu size={18} /> },
  { key: 'llm-providers', title: 'LLM Providers', description: 'Provider API keys and base URLs', icon: <FiKey size={18} /> },

  { key: 'tool-browser', title: 'Browser', description: 'Web browsing tool runtime status and behavior', icon: <FiCompass size={18} /> },
  { key: 'tool-brave', title: 'Brave Search', description: 'Brave API search tool', icon: <FiSearch size={18} /> },
  { key: 'tool-filesystem', title: 'Filesystem Tool', description: 'Sandbox file read/write operations', icon: <FiHardDrive size={18} /> },
  { key: 'tool-shell', title: 'Shell Tool', description: 'Sandbox shell command execution', icon: <FiTerminal size={18} /> },
  { key: 'tool-email', title: 'Email (IMAP/SMTP)', description: 'Email reading and sending integrations', icon: <FiMail size={18} /> },
  { key: 'tool-automation', title: 'Automation Tools', description: 'Skill management, transitions, and tier switching', icon: <FiShuffle size={18} /> },
  { key: 'tool-goals', title: 'Goal Management', description: 'Auto mode goal operations', icon: <FiTool size={18} /> },

  { key: 'voice-elevenlabs', title: 'ElevenLabs', description: 'TTS/STT provider settings', icon: <FiMic size={18} /> },
  { key: 'memory', title: 'Memory', description: 'Conversation memory persistence and retention', icon: <FiHardDrive size={18} /> },
  { key: 'skills', title: 'Skills Runtime', description: 'Enable skills and progressive loading behavior', icon: <FiTool size={18} /> },
  { key: 'turn', title: 'Turn Budget', description: 'Runtime limits for LLM/tool calls and deadline', icon: <FiCpu size={18} /> },
  { key: 'usage', title: 'Usage Tracking', description: 'Enable/disable analytics usage tracking', icon: <FiBarChart2 size={18} /> },
  { key: 'rag', title: 'RAG', description: 'LightRAG integration settings', icon: <FiGlobe size={18} /> },
  { key: 'mcp', title: 'MCP', description: 'Model Context Protocol runtime defaults', icon: <FiTool size={18} /> },
  { key: 'webhooks', title: 'Webhooks', description: 'Incoming hooks, auth, and delivery actions', icon: <FiGlobe size={18} /> },
  { key: 'auto', title: 'Auto Mode', description: 'Autonomous run behavior and constraints', icon: <FiPlayCircle size={18} /> },
  { key: 'advanced-rate-limit', title: 'Rate Limit', description: 'Request throttling configuration', icon: <FiShield size={18} /> },
  { key: 'advanced-security', title: 'Security', description: 'Input sanitization and injection guards', icon: <FiShield size={18} /> },
  { key: 'advanced-compaction', title: 'Compaction', description: 'Context compaction behavior', icon: <FiShield size={18} /> },
] as const;

type SettingsSectionKey = typeof SETTINGS_SECTIONS[number]['key'];

const SETTINGS_BLOCKS: Array<{
  key: string;
  title: string;
  description: string;
  sections: SettingsSectionKey[];
}> = [
  {
    key: 'core',
    title: 'Core',
    description: 'Main runtime settings and access configuration',
    sections: ['general', 'telegram', 'models', 'llm-providers'],
  },
  {
    key: 'tools',
    title: 'Tools',
    description: 'Tool-specific runtime behavior and integrations',
    sections: ['tool-browser', 'tool-brave', 'tool-filesystem', 'tool-shell', 'tool-email', 'tool-automation', 'tool-goals'],
  },
  {
    key: 'runtime',
    title: 'Runtime',
    description: 'Agent execution, memory, usage, and autonomy',
    sections: ['voice-elevenlabs', 'memory', 'skills', 'turn', 'usage', 'rag', 'mcp', 'auto', 'webhooks'],
  },
  {
    key: 'advanced',
    title: 'Advanced',
    description: 'Security and infrastructure guardrails',
    sections: ['advanced-rate-limit', 'advanced-security', 'advanced-compaction'],
  },
];

const KNOWN_LLM_PROVIDER_BASE_URLS: Record<string, string> = {
  openai: 'https://api.openai.com/v1',
  openrouter: 'https://openrouter.ai/api/v1',
  anthropic: 'https://api.anthropic.com',
  google: 'https://generativelanguage.googleapis.com/v1beta/openai',
  kimi: 'https://api.moonshot.ai/v1',
  groq: 'https://api.groq.com/openai/v1',
  together: 'https://api.together.xyz/v1',
  fireworks: 'https://api.fireworks.ai/inference/v1',
  deepseek: 'https://api.deepseek.com/v1',
  mistral: 'https://api.mistral.ai/v1',
  xai: 'https://api.x.ai/v1',
  perplexity: 'https://api.perplexity.ai',
  zhipu: 'https://open.bigmodel.cn/api/paas/v4',
  qwen: 'https://dashscope-intl.aliyuncs.com/compatible-mode/v1',
  cerebras: 'https://api.cerebras.ai/v1',
  deepinfra: 'https://api.deepinfra.com/v1/openai',
};

const KNOWN_LLM_PROVIDERS: string[] = Object.keys(KNOWN_LLM_PROVIDER_BASE_URLS);

function isSettingsSectionKey(value: string | undefined): value is SettingsSectionKey {
  return SETTINGS_SECTIONS.some((s) => s.key === value);
}

export default function SettingsPage() {
  const navigate = useNavigate();
  const { section } = useParams<{ section?: string }>();
  const { data: settings, isLoading: settingsLoading } = useSettings();
  const { data: rc, isLoading: rcLoading } = useRuntimeConfig();
  const { data: me } = useMe();
  const qc = useQueryClient();

  const selectedSection = isSettingsSectionKey(section) ? section : null;

  const sectionMeta = selectedSection
    ? SETTINGS_SECTIONS.find((s) => s.key === selectedSection) ?? null
    : null;

  if (settingsLoading || rcLoading) {
    return (
      <div>
        <div className="page-header">
          <h4>Settings</h4>
          <p className="text-body-secondary mb-0">Configure your GolemCore instance</p>
        </div>
        <Card className="settings-card">
          <Card.Body>
            <Placeholder as="div" animation="glow" className="mb-2"><Placeholder xs={8} /></Placeholder>
            <Placeholder as="div" animation="glow" className="mb-2"><Placeholder xs={12} /></Placeholder>
            <Placeholder as="div" animation="glow" className="mb-2"><Placeholder xs={12} /></Placeholder>
            <div className="d-flex justify-content-center pt-2">
              <Spinner animation="border" size="sm" />
            </div>
          </Card.Body>
        </Card>
      </div>
    );
  }

  if (!selectedSection) {
    return (
      <div>
        <div className="page-header">
          <h4>Settings</h4>
          <p className="text-body-secondary mb-0">Select a settings category</p>
        </div>
        {SETTINGS_BLOCKS.map((block) => (
          <div key={block.key} className="mb-4">
            <div className="mb-2">
              <h6 className="mb-1">{block.title}</h6>
              <p className="text-body-secondary small mb-0">{block.description}</p>
            </div>
            <Row className="g-3">
              {block.sections.map((sectionKey) => {
                const item = SETTINGS_SECTIONS.find((section) => section.key === sectionKey);
                if (!item) {
                  return null;
                }

                return (
                  <Col md={6} lg={4} xl={3} key={item.key}>
                    <Card className="settings-card h-100">
                      <Card.Body className="d-flex flex-column">
                        <div className="d-flex align-items-center gap-2 mb-2">
                          <span className="text-primary">{item.icon}</span>
                          <Card.Title className="h6 mb-0">{item.title}</Card.Title>
                        </div>
                        <Card.Text className="text-body-secondary small mb-3">{item.description}</Card.Text>
                        <div className="mt-auto">
                          <Button size="sm" variant="primary" onClick={() => navigate(`/settings/${item.key}`)}>
                            Open
                          </Button>
                        </div>
                      </Card.Body>
                    </Card>
                  </Col>
                );
              })}
            </Row>
          </div>
        ))}
      </div>
    );
  }

  return (
    <div>
      <div className="page-header">
        <h4>{sectionMeta?.title ?? 'Settings'}</h4>
        <p className="text-body-secondary mb-0">{sectionMeta?.description ?? 'Configure your GolemCore instance'}</p>
      </div>
      <div className="mb-3">
        <Button variant="secondary" size="sm" onClick={() => navigate('/settings')}>
          Back to catalog
        </Button>
      </div>

      {selectedSection === 'general' && <GeneralTab settings={settings} me={me} qc={qc} />}
      {selectedSection === 'telegram' && rc && <TelegramTab config={rc.telegram} voiceConfig={rc.voice} />}
      {selectedSection === 'models' && rc && <ModelsTab config={rc.modelRouter} llmConfig={rc.llm} />}
      {selectedSection === 'llm-providers' && rc && <LlmProvidersTab config={rc.llm} modelRouter={rc.modelRouter} />}

      {selectedSection === 'tool-browser' && rc && <ToolsTab config={rc.tools} mode="browser" />}
      {selectedSection === 'tool-brave' && rc && <ToolsTab config={rc.tools} mode="brave" />}
      {selectedSection === 'tool-filesystem' && rc && <ToolsTab config={rc.tools} mode="filesystem" />}
      {selectedSection === 'tool-shell' && rc && <ToolsTab config={rc.tools} mode="shell" />}
      {selectedSection === 'tool-email' && rc && <ToolsTab config={rc.tools} mode="email" />}
      {selectedSection === 'tool-automation' && rc && <ToolsTab config={rc.tools} mode="automation" />}
      {selectedSection === 'tool-goals' && rc && <ToolsTab config={rc.tools} mode="goals" />}

      {selectedSection === 'voice-elevenlabs' && rc && <VoiceTab config={rc.voice} />}
      {selectedSection === 'memory' && rc && <MemoryTab config={rc.memory} />}
      {selectedSection === 'skills' && rc && <SkillsTab config={rc.skills} />}
      {selectedSection === 'turn' && rc && <TurnTab config={rc.turn} />}
      {selectedSection === 'usage' && rc && <UsageTab config={rc.usage} />}
      {selectedSection === 'rag' && rc && <RagTab config={rc.rag} />}
      {selectedSection === 'mcp' && rc && <McpTab config={rc.mcp} />}
      {selectedSection === 'webhooks' && <WebhooksTab />}
      {selectedSection === 'auto' && rc && <AutoModeTab config={rc.autoMode} />}

      {selectedSection === 'advanced-rate-limit' && rc && (
        <AdvancedTab rateLimit={rc.rateLimit} security={rc.security} compaction={rc.compaction} mode="rateLimit" />
      )}
      {selectedSection === 'advanced-security' && rc && (
        <AdvancedTab rateLimit={rc.rateLimit} security={rc.security} compaction={rc.compaction} mode="security" />
      )}
      {selectedSection === 'advanced-compaction' && rc && (
        <AdvancedTab rateLimit={rc.rateLimit} security={rc.security} compaction={rc.compaction} mode="compaction" />
      )}
    </div>
  );
}

// ==================== General Tab ====================

function GeneralTab({ settings, me, qc }: { settings: any; me: any; qc: any }) {
  const updatePrefs = useUpdatePreferences();
  const [language, setLanguage] = useState(settings?.language ?? 'en');
  const [timezone, setTimezone] = useState(settings?.timezone ?? 'UTC');
  const [oldPwd, setOldPwd] = useState('');
  const [newPwd, setNewPwd] = useState('');

  useEffect(() => {
    setLanguage(settings?.language ?? 'en');
    setTimezone(settings?.timezone ?? 'UTC');
  }, [settings]);

  const isPrefsDirty = language !== (settings?.language ?? 'en') || timezone !== (settings?.timezone ?? 'UTC');

  const handleSavePrefs = async (e: React.FormEvent) => {
    e.preventDefault();
    await updatePrefs.mutateAsync({ language, timezone });
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
                  value={language}
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
                  value={timezone}
                  onChange={(e) => setTimezone(e.target.value)}
                  placeholder="UTC"
                />
              </Form.Group>
              <div className="d-flex align-items-center gap-2">
                <Button type="submit" variant="primary" size="sm" disabled={!isPrefsDirty || updatePrefs.isPending}>
                  {updatePrefs.isPending ? 'Saving...' : 'Save Preferences'}
                </Button>
                <SaveStateHint isDirty={isPrefsDirty} />
              </div>
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

function TelegramTab({ config, voiceConfig }: { config: TelegramConfig; voiceConfig: VoiceConfig }) {
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
      token: token || null,
      authMode,
      allowedUsers: allowedUserId ? [allowedUserId] : [],
    }),
    [config, enabled, token, authMode, allowedUserId],
  );

  const initialConfig = useMemo(
    () => ({
      ...config,
      enabled: config.enabled ?? false,
      token: config.token ?? null,
      authMode: config.authMode ?? 'invite_only',
      allowedUsers: (config.allowedUsers?.[0] ?? '').replace(/\D/g, '')
        ? [(config.allowedUsers?.[0] ?? '').replace(/\D/g, '')]
        : [],
    }),
    [config],
  );

  const isTelegramDirty = hasDiff(currentConfig, initialConfig);

  const handleSave = async () => {
    await updateTelegram.mutateAsync(currentConfig);
    await updateVoice.mutateAsync({
      ...voiceConfig,
      telegramRespondWithVoice,
      telegramTranscribeIncoming,
    });
    toast.success('Telegram settings saved');
  };

  const handleRevokeCode = async () => {
    if (!revokeCode) {
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
                onChange={(e) => setAuthMode(e.target.value as 'user' | 'invite_only')}
              >
                <option value="user">User</option>
                <option value="invite_only">Invite Only</option>
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

        {authMode === 'invite_only' && (
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
                      <td className="small text-body-secondary">{new Date(ic.createdAt).toLocaleDateString()}</td>
                      <td className="text-end">
                        <Button size="sm" variant="secondary" className="me-2"
                          onClick={() => { navigator.clipboard.writeText(ic.code); toast.success('Copied!'); }}>Copy</Button>
                        <Button size="sm" variant="danger"
                          onClick={() => setRevokeCode(ic.code)}>Revoke</Button>
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
              label={<>Respond with Voice <Tip text="If incoming message is voice, bot can answer with synthesized voice." /></>}
              checked={telegramRespondWithVoice}
              onChange={(e) => setTelegramRespondWithVoice(e.target.checked)}
              className="mb-2"
            />
            <Form.Check type="switch"
              label={<>Transcribe Incoming Voice <Tip text="Enable transcription of incoming voice messages before processing." /></>}
              checked={telegramTranscribeIncoming}
              onChange={(e) => setTelegramTranscribeIncoming(e.target.checked)}
            />
          </Card.Body>
        </Card>

        <div className="d-flex flex-wrap align-items-center gap-2 pt-2 border-top">
          <Button variant="primary" size="sm" onClick={handleSave} disabled={!isTelegramDirty || updateTelegram.isPending}>
            {updateTelegram.isPending ? 'Saving...' : 'Save'}
          </Button>
          <Button variant="warning" size="sm"
            onClick={() => restart.mutate(undefined, { onSuccess: () => toast.success('Telegram restarting...') })}>
            Restart Bot
          </Button>
          <SaveStateHint isDirty={isTelegramDirty} />
        </div>
      </Card.Body>

      <ConfirmModal
        show={!!revokeCode}
        title="Revoke Invite Code"
        message="This invite code will stop working immediately. This action cannot be undone."
        confirmLabel="Revoke"
        confirmVariant="danger"
        isProcessing={delInvite.isPending}
        onConfirm={handleRevokeCode}
        onCancel={() => setRevokeCode(null)}
      />
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

function ModelsTab({ config, llmConfig }: { config: ModelRouterConfig; llmConfig: LlmConfig }) {
  const updateRouter = useUpdateModelRouter();
  const { data: available } = useAvailableModels();
  const [form, setForm] = useState<ModelRouterConfig>({ ...config });

  useEffect(() => { setForm({ ...config }); }, [config]);

  // Group models by provider
  const configuredProviderNames = useMemo(() => {
    return Object.keys(llmConfig.providers ?? {});
  }, [llmConfig]);

  const providers = useMemo(() => {
    if (!available) {
      return {} as Record<string, AvailableModel[]>;
    }

    const availableByProvider = available as Record<string, AvailableModel[]>;
    const configuredSet = new Set(configuredProviderNames);

    return Object.fromEntries(
      Object.entries(availableByProvider).filter(([provider]) => configuredSet.has(provider)),
    );
  }, [available, configuredProviderNames]);

  const providerNames = useMemo(() => Object.keys(providers), [providers]);
  const isModelsDirty = useMemo(() => hasDiff(form, config), [form, config]);

  const handleSave = async () => {
    await updateRouter.mutateAsync(form);
    toast.success('Model router settings saved');
  };

  const tierCards = [
    { key: 'balanced', label: 'Balanced', color: 'primary', modelField: 'balancedModel' as const, reasoningField: 'balancedModelReasoning' as const },
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
        {providerNames.length === 0 && (
          <Col xs={12}>
            <Card className="settings-card">
              <Card.Body className="py-2">
                <small className="text-body-secondary">
                  No LLM providers configured. Add at least one provider in `LLM Providers` to select models here.
                </small>
              </Card.Body>
            </Card>
          </Col>
        )}

        <Col md={6} lg={3}>
          <TierModelCard
            label="Routing"
            color="dark"
            providers={providers}
            providerNames={providerNames}
            modelValue={form.routingModel ?? ''}
            reasoningValue={form.routingModelReasoning ?? ''}
            onModelChange={(val) => setForm({ ...form, routingModel: val || null, routingModelReasoning: null })}
            onReasoningChange={(val) => setForm({ ...form, routingModelReasoning: val || null })}
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

      <div className="d-flex align-items-center gap-2">
        <Button variant="primary" size="sm" onClick={handleSave} disabled={!isModelsDirty || updateRouter.isPending}>
          {updateRouter.isPending ? 'Saving...' : 'Save Model Configuration'}
        </Button>
        <SaveStateHint isDirty={isModelsDirty} />
      </div>
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
    if (!modelValue) {return providerNames[0] ?? '';}
    for (const [prov, models] of Object.entries(providers)) {
      if (models.some((m) => m.id === modelValue)) {return prov;}
    }
    return providerNames[0] ?? '';
  }, [modelValue, providers, providerNames]);

  const [provider, setProvider] = useState(selectedProvider);

  useEffect(() => { setProvider(selectedProvider); }, [selectedProvider]);

  const modelsForProvider = providers[provider] ?? [];
  const selectedModel = modelsForProvider.find((m) => m.id === modelValue);
  const reasoningLevels = selectedModel?.reasoningLevels ?? [];
  const hasProviders = providerNames.length > 0;

  return (
    <Card className="tier-card h-100">
      <Card.Body className="p-3">
        <Badge bg={color} className="mb-2">{label}</Badge>

        <Form.Group className="mb-2">
          <Form.Label className="small fw-medium mb-1">Provider</Form.Label>
          <Form.Select size="sm" value={provider}
            disabled={!hasProviders}
            onChange={(e) => { setProvider(e.target.value); onModelChange(''); }}>
            {!hasProviders && <option value="">No providers</option>}
            {providerNames.map((p) => (
              <option key={p} value={p}>{p}</option>
            ))}
          </Form.Select>
        </Form.Group>

        <Form.Group className="mb-2">
          <Form.Label className="small fw-medium mb-1">Model</Form.Label>
          <Form.Select size="sm" value={modelValue}
            disabled={!hasProviders}
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

function LlmProvidersTab({ config, modelRouter }: { config: LlmConfig; modelRouter: ModelRouterConfig }) {
  const updateLlm = useUpdateLlm();
  const [form, setForm] = useState<LlmConfig>({ providers: { ...(config.providers ?? {}) } });
  const [newProviderName, setNewProviderName] = useState('');
  const [showKeys, setShowKeys] = useState<Record<string, boolean>>({});
  const isDirty = useMemo(() => hasDiff(form, config), [form, config]);

  useEffect(() => {
    setForm({ providers: { ...(config.providers ?? {}) } });
  }, [config]);

  const providerNames = Object.keys(form.providers ?? {});
  const knownProviderSuggestions = useMemo(() => {
    const combinedProviderNames = [...KNOWN_LLM_PROVIDERS, ...providerNames];
    return Array.from(new Set(combinedProviderNames)).sort();
  }, [providerNames]);

  const addProvider = () => {
    const name = newProviderName.trim();
    if (!name) {
      return;
    }
    const normalizedName = name.toLowerCase();
    if (!/^[a-z0-9][a-z0-9_-]*$/.test(normalizedName)) {
      toast.error('Provider name must match [a-z0-9][a-z0-9_-]*');
      return;
    }
    if (form.providers[normalizedName]) {
      toast.error('Provider already exists');
      return;
    }
    setForm({
      providers: {
        ...form.providers,
        [normalizedName]: {
          apiKey: null,
          apiKeyPresent: false,
          baseUrl: KNOWN_LLM_PROVIDER_BASE_URLS[normalizedName] ?? null,
          requestTimeoutSeconds: 300,
        },
      },
    });
    setNewProviderName('');
  };

  const usedProviders = useMemo(() => {
    const used = new Set<string>();
    const models = [
      modelRouter.routingModel,
      modelRouter.balancedModel,
      modelRouter.smartModel,
      modelRouter.codingModel,
      modelRouter.deepModel,
    ].filter(Boolean) as string[];
    models.forEach((m) => {
      const idx = m.indexOf('/');
      if (idx > 0) {
        used.add(m.substring(0, idx));
      }
    });
    return used;
  }, [modelRouter]);

  const handleSave = async () => {
    await updateLlm.mutateAsync(form);
    toast.success('LLM provider settings saved');
  };

  return (
    <Card className="settings-card">
      <Card.Body>
        <Card.Title className="h6 mb-3">LLM Providers</Card.Title>
        <div className="small text-body-secondary mb-3">
          Runtime provider list and credentials. No fallback from application properties.
        </div>

        <InputGroup className="mb-3" size="sm">
          <Form.Control
            placeholder="new provider name (e.g. perplexity)"
            list="known-llm-providers"
            value={newProviderName}
            onChange={(e) => setNewProviderName(e.target.value)}
          />
          <Button variant="secondary" onClick={addProvider}>Add provider</Button>
        </InputGroup>
        <datalist id="known-llm-providers">
          {knownProviderSuggestions.map((providerName) => (
            <option key={providerName} value={providerName} />
          ))}
        </datalist>

        <Row className="g-3">
          {providerNames.map((provider) => (
            <Col md={6} key={provider}>
              <Card className="h-100">
                <Card.Body>
                  <div className="d-flex align-items-center justify-content-between mb-3">
                    <Card.Title className="h6 text-capitalize mb-0">{provider}</Card.Title>
                    <Button
                      variant="secondary"
                      size="sm"
                      disabled={usedProviders.has(provider)}
                      title={usedProviders.has(provider)
                        ? 'Provider is used by model router tiers and cannot be removed'
                        : 'Remove provider'}
                      onClick={() => {
                        const next = { ...form.providers };
                        delete next[provider];
                        setForm({ providers: next });
                      }}
                    >
                      Remove
                    </Button>
                  </div>
                  <Form.Group className="mb-2">
                    <Form.Label className="small fw-medium d-flex align-items-center gap-2">
                      <span>API Key</span>
                      {form.providers[provider]?.apiKeyPresent && (
                        <Badge bg="success-subtle" text="success">Secret set</Badge>
                      )}
                      {!!form.providers[provider]?.apiKey && (
                        <Badge bg="warning-subtle" text="warning">Will update on save</Badge>
                      )}
                    </Form.Label>
                    <InputGroup size="sm">
                      <Form.Control
                        name={`llm-api-key-${provider}`}
                        autoComplete="new-password"
                        autoCorrect="off"
                        autoCapitalize="off"
                        spellCheck={false}
                        data-lpignore="true"
                        placeholder={form.providers[provider]?.apiKeyPresent
                          ? 'Secret is configured (hidden)'
                          : ''}
                        type={showKeys[provider] ? 'text' : 'password'}
                        value={form.providers[provider]?.apiKey ?? ''}
                        onChange={(e) => setForm({
                          ...form,
                          providers: {
                            ...form.providers,
                            [provider]: { ...form.providers[provider], apiKey: e.target.value || null },
                          },
                        })}
                      />
                      <Button
                        variant="secondary"
                        onClick={() => setShowKeys({ ...showKeys, [provider]: !showKeys[provider] })}
                      >
                        {showKeys[provider] ? 'Hide' : 'Show'}
                      </Button>
                    </InputGroup>
                  </Form.Group>
                  <Form.Group className="mb-2">
                    <Form.Label className="small fw-medium">Base URL</Form.Label>
                    <Form.Control
                      size="sm"
                      value={form.providers[provider]?.baseUrl ?? ''}
                      onChange={(e) => setForm({
                        ...form,
                        providers: {
                          ...form.providers,
                          [provider]: { ...form.providers[provider], baseUrl: e.target.value || null },
                        },
                      })}
                    />
                  </Form.Group>
                  <Form.Group>
                    <Form.Label className="small fw-medium">Request Timeout (seconds)</Form.Label>
                    <Form.Control
                      size="sm"
                      type="number"
                      min={1}
                      max={3600}
                      value={form.providers[provider]?.requestTimeoutSeconds ?? 300}
                      onChange={(e) => setForm({
                        ...form,
                        providers: {
                          ...form.providers,
                          [provider]: {
                            ...form.providers[provider],
                            requestTimeoutSeconds: parseInt(e.target.value, 10) || 300,
                          },
                        },
                      })}
                    />
                  </Form.Group>
                </Card.Body>
              </Card>
            </Col>
          ))}
        </Row>

        <div className="d-flex align-items-center gap-2 mt-3">
          <Button variant="primary" size="sm" onClick={handleSave} disabled={!isDirty || updateLlm.isPending}>
            {updateLlm.isPending ? 'Saving...' : 'Save'}
          </Button>
          <SaveStateHint isDirty={isDirty} />
        </div>
      </Card.Body>
    </Card>
  );
}

// ==================== Tools Tab ====================

type ToolsMode =
  | 'all'
  | 'browser'
  | 'brave'
  | 'filesystem'
  | 'shell'
  | 'email'
  | 'automation'
  | 'skills'
  | 'skillTransition'
  | 'tier'
  | 'goals';

function ToolsTab({ config, mode = 'all' }: { config: ToolsConfig; mode?: ToolsMode }) {
  const updateTools = useUpdateTools();
  const browserHealthPing = useBrowserHealthPing();
  const [form, setForm] = useState<ToolsConfig>({ ...config });
  const isToolsDirty = useMemo(() => hasDiff(form, config), [form, config]);

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

  const keyByMode: Partial<Record<ToolsMode, typeof tools[number]['key']>> = {
    filesystem: 'filesystemEnabled',
    shell: 'shellEnabled',
    brave: 'braveSearchEnabled',
    skills: 'skillManagementEnabled',
    skillTransition: 'skillTransitionEnabled',
    tier: 'tierEnabled',
    goals: 'goalManagementEnabled',
  };

  const selectedToolKey = keyByMode[mode];
  const selectedTool = selectedToolKey ? tools.find((t) => t.key === selectedToolKey) : null;

  const showToolToggles = mode === 'all';
  const showBraveApiKey = mode === 'all' || mode === 'brave';
  const showImap = mode === 'all' || mode === 'email';
  const showSmtp = mode === 'all' || mode === 'email';
  const showBrowserInfo = mode === 'browser';
  const showAutomationGroup = mode === 'automation';
  const showInlineEnableToggles = mode === 'all';
  const automationTools = tools.filter((t) =>
    t.key === 'skillManagementEnabled' || t.key === 'skillTransitionEnabled' || t.key === 'tierEnabled');

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
      {showToolToggles && (
        <Card className="settings-card mb-3">
          <Card.Body>
            <Card.Title className="h6 mb-3">Tool Toggles</Card.Title>
            <div className="mb-0">
              {tools.map(({ key, label, desc, tip }) => (
                <div key={key} className="d-flex align-items-start py-2 border-bottom">
                  <Form.Check type="switch"
                    checked={form[key] ?? true}
                    onChange={(e) => setForm({ ...form, [key]: e.target.checked })}
                    className="me-3"
                  />
                  <div>
                    <div className="fw-medium small">{label} <Tip text={tip} /></div>
                    <div className="meta-text">{desc}</div>
                  </div>
                </div>
              ))}
            </div>
          </Card.Body>
        </Card>
      )}

      {!showToolToggles && selectedTool && mode !== 'brave' && (
        <Card className="settings-card mb-3">
          <Card.Body>
            <Card.Title className="h6 mb-3">{selectedTool.label}</Card.Title>
            <div className="mb-2">
              <Badge bg={(form[selectedTool.key] ?? true) ? 'success' : 'secondary'}>
                {(form[selectedTool.key] ?? true) ? 'Enabled' : 'Disabled'}
              </Badge>
            </div>
            <div className="meta-text mt-2">{selectedTool.desc}</div>
            <div className="meta-text mt-2">Enable/disable is managed in the Automation Tools card.</div>
          </Card.Body>
        </Card>
      )}

      {mode === 'brave' && (
        <Card className="settings-card mb-3">
          <Card.Body>
            <Card.Title className="h6 mb-3">Brave Search</Card.Title>
            <Form.Check type="switch"
              label={<>Enable Brave Search <Tip text="Enable Brave as active browser search provider" /></>}
              checked={form.braveSearchEnabled ?? true}
              onChange={(e) => setForm({ ...form, braveSearchEnabled: e.target.checked })}
              className="mb-3"
            />
            <Form.Group>
              <Form.Label className="small fw-medium">
                Brave Search API Key <Tip text="Get your free API key at brave.com/search/api" />
              </Form.Label>
              <Form.Control size="sm" type="password" value={form.braveSearchApiKey ?? ''}
                onChange={(e) => setForm({ ...form, braveSearchApiKey: e.target.value || null })}
                placeholder="BSA-..." />
            </Form.Group>
          </Card.Body>
        </Card>
      )}

      {showAutomationGroup && (
        <Card className="settings-card mb-3">
          <Card.Body>
            <Card.Title className="h6 mb-3">Automation Tools</Card.Title>
            <div className="small text-body-secondary mb-3">All tool enable/disable flags are managed here.</div>
            <div className="d-flex align-items-start py-2 border-bottom">
              <Form.Check type="switch"
                checked={form.filesystemEnabled ?? true}
                onChange={(e) => setForm({ ...form, filesystemEnabled: e.target.checked })}
                className="me-3"
              />
              <div>
                <div className="fw-medium small">Filesystem <Tip text="Read/write files in sandboxed workspace" /></div>
                <div className="meta-text">Enable filesystem tool access.</div>
              </div>
            </div>
            <div className="d-flex align-items-start py-2 border-bottom">
              <Form.Check type="switch"
                checked={form.shellEnabled ?? true}
                onChange={(e) => setForm({ ...form, shellEnabled: e.target.checked })}
                className="me-3"
              />
              <div>
                <div className="fw-medium small">Shell <Tip text="Execute shell commands in sandbox" /></div>
                <div className="meta-text">Enable shell tool access.</div>
              </div>
            </div>
            {automationTools.map(({ key, label, desc, tip }) => (
              <div key={key} className="d-flex align-items-start py-2 border-bottom">
                <Form.Check type="switch"
                  checked={form[key] ?? true}
                  onChange={(e) => setForm({ ...form, [key]: e.target.checked })}
                  className="me-3"
                />
                <div>
                  <div className="fw-medium small">{label} <Tip text={tip} /></div>
                  <div className="meta-text">{desc}</div>
                </div>
              </div>
            ))}
            <div className="d-flex align-items-start py-2 border-bottom">
              <Form.Check type="switch"
                checked={form.goalManagementEnabled ?? true}
                onChange={(e) => setForm({ ...form, goalManagementEnabled: e.target.checked })}
                className="me-3"
              />
              <div>
                <div className="fw-medium small">Goal Management <Tip text="Auto mode goal management operations" /></div>
                <div className="meta-text">Enable goal management tool.</div>
              </div>
            </div>
            <div className="d-flex align-items-start py-2 border-bottom">
              <Form.Check type="switch"
                checked={form.imap?.enabled ?? false}
                onChange={(e) => setForm({ ...form, imap: { ...form.imap, enabled: e.target.checked } })}
                className="me-3"
              />
              <div>
                <div className="fw-medium small">IMAP <Tip text="Email reading integration" /></div>
                <div className="meta-text">Enable IMAP settings and tool operations.</div>
              </div>
            </div>
            <div className="d-flex align-items-start py-2">
              <Form.Check type="switch"
                checked={form.smtp?.enabled ?? false}
                onChange={(e) => setForm({ ...form, smtp: { ...form.smtp, enabled: e.target.checked } })}
                className="me-3"
              />
              <div>
                <div className="fw-medium small">SMTP <Tip text="Email sending integration" /></div>
                <div className="meta-text">Enable SMTP settings and tool operations.</div>
              </div>
            </div>
          </Card.Body>
        </Card>
      )}

      {showBrowserInfo && (
        <Card className="settings-card mb-3">
          <Card.Body>
            <Card.Title className="h6 mb-2">Browser Tool</Card.Title>
            <Row className="g-3">
              <Col md={6}>
                <Form.Group>
                  <Form.Label className="small fw-medium">
                    Browser Engine <Tip text="Browser engine implementation." />
                  </Form.Label>
                  <Form.Select size="sm"
                    value={form.browserType ?? 'playwright'}
                    onChange={(e) => setForm({ ...form, browserType: e.target.value })}>
                    <option value="playwright">Playwright</option>
                  </Form.Select>
                </Form.Group>
              </Col>
              <Col md={6}>
                <Form.Group>
                  <Form.Label className="small fw-medium">
                    Browser API Provider <Tip text="Active provider for browser/search tools." />
                  </Form.Label>
                  <Form.Select size="sm"
                    value={form.browserApiProvider ?? 'brave'}
                    onChange={(e) => setForm({ ...form, browserApiProvider: e.target.value })}>
                    <option value="brave">Brave</option>
                  </Form.Select>
                </Form.Group>
              </Col>
              <Col md={6}>
                <Form.Group>
                  <Form.Label className="small fw-medium">
                    Timeout (ms) <Tip text="Page operation timeout in milliseconds." />
                  </Form.Label>
                  <Form.Control
                    size="sm"
                    type="number"
                    min={1000}
                    max={120000}
                    step={500}
                    value={form.browserTimeout ?? 30000}
                    onChange={(e) => setForm({ ...form, browserTimeout: Number(e.target.value) })}
                  />
                </Form.Group>
              </Col>
              <Col md={6}>
                <Form.Check type="switch"
                  label={<>Headless Browser <Tip text="Run browser automation in headless mode" /></>}
                  checked={form.browserHeadless ?? true}
                  onChange={(e) => setForm({ ...form, browserHeadless: e.target.checked })}
                  className="mt-md-4 mt-2"
                />
              </Col>
              <Col md={12}>
                <Form.Group>
                  <Form.Label className="small fw-medium">
                    User-Agent <Tip text="User-Agent string used for browser sessions." />
                  </Form.Label>
                  <Form.Control
                    size="sm"
                    value={form.browserUserAgent ?? ''}
                    onChange={(e) => setForm({ ...form, browserUserAgent: e.target.value || null })}
                  />
                </Form.Group>
              </Col>
            </Row>

            <div className="mt-3 pt-3 border-top">
              <div className="d-flex align-items-center gap-2 mb-2">
                <Button
                  variant="secondary"
                  size="sm"
                  onClick={() => browserHealthPing.mutate()}
                  disabled={browserHealthPing.isPending}
                >
                  {browserHealthPing.isPending ? 'Pinging...' : 'Ping Browser'}
                </Button>
                {browserHealthPing.data && (
                  <Badge bg={browserHealthPing.data.ok ? 'success' : 'danger'}>
                    {browserHealthPing.data.ok ? 'Healthy' : 'Failed'}
                  </Badge>
                )}
              </div>
              {browserHealthPing.data && (
                <div className="meta-text">
                  {browserHealthPing.data.message}
                </div>
              )}
            </div>
          </Card.Body>
        </Card>
      )}

      {showBraveApiKey && mode !== 'brave' && (form.braveSearchEnabled ?? false) && (
        <Card className="settings-card mb-3">
          <Card.Body>
            <Card.Title className="h6 mb-3">Brave Search Credentials</Card.Title>
            <Form.Group>
              <Form.Label className="small fw-medium">
                Brave Search API Key <Tip text="Get your free API key at brave.com/search/api" />
              </Form.Label>
              <Form.Control size="sm" type="password" value={form.braveSearchApiKey ?? ''}
                onChange={(e) => setForm({ ...form, braveSearchApiKey: e.target.value || null })}
                placeholder="BSA-..." />
            </Form.Group>
          </Card.Body>
        </Card>
      )}

      {/* IMAP Settings */}
      {showImap && <Card className="settings-card mb-3">
        <Card.Body>
          <Card.Title className="h6 mb-3">
            IMAP (Email Reading) <Tip text="Read emails from an IMAP mailbox. The bot can search, read, and list emails." />
          </Card.Title>
          {!showInlineEnableToggles && (
            <div className="mb-3">
              <Badge bg={(form.imap?.enabled ?? false) ? 'success' : 'secondary'}>
                {(form.imap?.enabled ?? false) ? 'Enabled' : 'Disabled'}
              </Badge>
              <div className="meta-text mt-2">Enable/disable is managed in the Automation Tools card.</div>
            </div>
          )}
          {showInlineEnableToggles && (
            <Form.Check type="switch" label="Enable IMAP" checked={form.imap?.enabled ?? false}
              onChange={(e) => updateImap({ enabled: e.target.checked })} className="mb-3" />
          )}
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
      </Card>}

      {/* SMTP Settings */}
      {showSmtp && <Card className="settings-card mb-3">
        <Card.Body>
          <Card.Title className="h6 mb-3">
            SMTP (Email Sending) <Tip text="Send emails via SMTP. The bot can compose and send emails on your behalf." />
          </Card.Title>
          {!showInlineEnableToggles && (
            <div className="mb-3">
              <Badge bg={(form.smtp?.enabled ?? false) ? 'success' : 'secondary'}>
                {(form.smtp?.enabled ?? false) ? 'Enabled' : 'Disabled'}
              </Badge>
              <div className="meta-text mt-2">Enable/disable is managed in the Automation Tools card.</div>
            </div>
          )}
          {showInlineEnableToggles && (
            <Form.Check type="switch" label="Enable SMTP" checked={form.smtp?.enabled ?? false}
              onChange={(e) => updateSmtp({ enabled: e.target.checked })} className="mb-3" />
          )}
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
      </Card>}

      {mode !== 'browser' && (
        <div className="d-flex align-items-center gap-2">
          <Button variant="primary" size="sm" onClick={handleSave} disabled={!isToolsDirty || updateTools.isPending}>
            {updateTools.isPending ? 'Saving...' : 'Save Tool Settings'}
          </Button>
          <SaveStateHint isDirty={isToolsDirty} />
        </div>
      )}
    </>
  );
}

// ==================== Voice Tab ====================

function VoiceTab({ config }: { config: VoiceConfig }) {
  const updateVoice = useUpdateVoice();
  const [form, setForm] = useState<VoiceConfig>({ ...config });
  const [showKey, setShowKey] = useState(false);
  const isVoiceDirty = useMemo(() => hasDiff(form, config), [form, config]);

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

        <div className="mt-3 d-flex align-items-center gap-2">
          <Button variant="primary" size="sm" onClick={handleSave} disabled={!isVoiceDirty || updateVoice.isPending}>
            {updateVoice.isPending ? 'Saving...' : 'Save'}
          </Button>
          <SaveStateHint isDirty={isVoiceDirty} />
        </div>
      </Card.Body>
    </Card>
  );
}

function MemoryTab({ config }: { config: MemoryConfig }) {
  const updateMemory = useUpdateMemory();
  const [form, setForm] = useState<MemoryConfig>({ ...config });
  const isDirty = useMemo(() => hasDiff(form, config), [form, config]);

  useEffect(() => {
    setForm({ ...config });
  }, [config]);

  const handleSave = async () => {
    await updateMemory.mutateAsync(form);
    toast.success('Memory settings saved');
  };

  return (
    <Card className="settings-card">
      <Card.Body>
        <Card.Title className="h6 mb-3">Memory</Card.Title>
        <Form.Check
          type="switch"
          label={<>Enable Memory <Tip text="Persist user/assistant exchanges into long-term notes and include memory context in prompts." /></>}
          checked={form.enabled ?? true}
          onChange={(e) => setForm({ ...form, enabled: e.target.checked })}
          className="mb-3"
        />
        <Form.Group className="mb-3">
          <Form.Label className="small fw-medium">
            Recent Days <Tip text="How many previous daily memory files to include in context." />
          </Form.Label>
          <Form.Control
            size="sm"
            type="number"
            min={1}
            max={90}
            value={form.recentDays ?? 7}
            onChange={(e) => setForm({ ...form, recentDays: parseInt(e.target.value, 10) || null })}
          />
        </Form.Group>
        <div className="d-flex align-items-center gap-2">
          <Button variant="primary" size="sm" onClick={handleSave} disabled={!isDirty || updateMemory.isPending}>
            {updateMemory.isPending ? 'Saving...' : 'Save'}
          </Button>
          <SaveStateHint isDirty={isDirty} />
        </div>
      </Card.Body>
    </Card>
  );
}

function SkillsTab({ config }: { config: SkillsConfig }) {
  const updateSkills = useUpdateSkills();
  const [form, setForm] = useState<SkillsConfig>({ ...config });
  const isDirty = useMemo(() => hasDiff(form, config), [form, config]);

  useEffect(() => {
    setForm({ ...config });
  }, [config]);

  const handleSave = async () => {
    await updateSkills.mutateAsync(form);
    toast.success('Skills runtime settings saved');
  };

  return (
    <Card className="settings-card">
      <Card.Body>
        <Card.Title className="h6 mb-3">Skills Runtime</Card.Title>
        <Form.Check
          type="switch"
          label={<>Enable Skills <Tip text="Allow loading and using skills from storage at runtime." /></>}
          checked={form.enabled ?? true}
          onChange={(e) => setForm({ ...form, enabled: e.target.checked })}
          className="mb-3"
        />
        <Form.Check
          type="switch"
          label={<>Progressive Loading <Tip text="Expose skill summaries in context instead of full skill content until routing selects one." /></>}
          checked={form.progressiveLoading ?? true}
          onChange={(e) => setForm({ ...form, progressiveLoading: e.target.checked })}
          className="mb-3"
        />
        <div className="d-flex align-items-center gap-2">
          <Button variant="primary" size="sm" onClick={handleSave} disabled={!isDirty || updateSkills.isPending}>
            {updateSkills.isPending ? 'Saving...' : 'Save'}
          </Button>
          <SaveStateHint isDirty={isDirty} />
        </div>
      </Card.Body>
    </Card>
  );
}

function TurnTab({ config }: { config: TurnConfig }) {
  const updateTurn = useUpdateTurn();
  const [form, setForm] = useState<TurnConfig>({ ...config });
  const isDirty = useMemo(() => hasDiff(form, config), [form, config]);

  useEffect(() => {
    setForm({ ...config });
  }, [config]);

  const handleSave = async () => {
    await updateTurn.mutateAsync(form);
    toast.success('Turn budget settings saved');
  };

  return (
    <Card className="settings-card">
      <Card.Body>
        <Card.Title className="h6 mb-3">Turn Budget</Card.Title>
        <Row className="g-3 mb-3">
          <Col md={4}>
            <Form.Group>
              <Form.Label className="small fw-medium">
                Max LLM Calls <Tip text="Maximum LLM requests within a single turn." />
              </Form.Label>
              <Form.Control
                size="sm"
                type="number"
                min={1}
                value={form.maxLlmCalls ?? 200}
                onChange={(e) => setForm({ ...form, maxLlmCalls: parseInt(e.target.value, 10) || null })}
              />
            </Form.Group>
          </Col>
          <Col md={4}>
            <Form.Group>
              <Form.Label className="small fw-medium">
                Max Tool Executions <Tip text="Maximum tool executions within a single turn." />
              </Form.Label>
              <Form.Control
                size="sm"
                type="number"
                min={1}
                value={form.maxToolExecutions ?? 500}
                onChange={(e) => setForm({ ...form, maxToolExecutions: parseInt(e.target.value, 10) || null })}
              />
            </Form.Group>
          </Col>
          <Col md={4}>
            <Form.Group>
              <Form.Label className="small fw-medium">
                Deadline <Tip text="Turn deadline in ISO-8601 duration format, e.g. PT1H or PT30M." />
              </Form.Label>
              <Form.Control
                size="sm"
                value={form.deadline ?? 'PT1H'}
                onChange={(e) => setForm({ ...form, deadline: e.target.value || null })}
                placeholder="PT1H"
              />
            </Form.Group>
          </Col>
        </Row>
        <div className="d-flex align-items-center gap-2">
          <Button variant="primary" size="sm" onClick={handleSave} disabled={!isDirty || updateTurn.isPending}>
            {updateTurn.isPending ? 'Saving...' : 'Save'}
          </Button>
          <SaveStateHint isDirty={isDirty} />
        </div>
      </Card.Body>
    </Card>
  );
}

function UsageTab({ config }: { config: UsageConfig }) {
  const updateUsage = useUpdateUsage();
  const [form, setForm] = useState<UsageConfig>({ ...config });
  const isDirty = useMemo(() => hasDiff(form, config), [form, config]);

  useEffect(() => {
    setForm({ ...config });
  }, [config]);

  const handleSave = async () => {
    await updateUsage.mutateAsync(form);
    toast.success('Usage settings saved');
  };

  return (
    <Card className="settings-card">
      <Card.Body>
        <Card.Title className="h6 mb-3">Usage Tracking</Card.Title>
        <Form.Check
          type="switch"
          label={<>Enable Usage Tracking <Tip text="Enable collection of LLM request/token/latency metrics for Analytics." /></>}
          checked={form.enabled ?? true}
          onChange={(e) => setForm({ ...form, enabled: e.target.checked })}
          className="mb-3"
        />
        <div className="d-flex align-items-center gap-2">
          <Button variant="primary" size="sm" onClick={handleSave} disabled={!isDirty || updateUsage.isPending}>
            {updateUsage.isPending ? 'Saving...' : 'Save'}
          </Button>
          <SaveStateHint isDirty={isDirty} />
        </div>
      </Card.Body>
    </Card>
  );
}

function RagTab({ config }: { config: RagConfig }) {
  const updateRag = useUpdateRag();
  const [form, setForm] = useState<RagConfig>({ ...config });
  const [showApiKey, setShowApiKey] = useState(false);
  const isDirty = useMemo(() => hasDiff(form, config), [form, config]);

  useEffect(() => {
    setForm({ ...config });
  }, [config]);

  const handleSave = async () => {
    await updateRag.mutateAsync(form);
    toast.success('RAG settings saved');
  };

  return (
    <Card className="settings-card">
      <Card.Body>
        <Card.Title className="h6 mb-3">RAG (LightRAG)</Card.Title>

        <Form.Check
          type="switch"
          label={<>Enable RAG <Tip text="Enable retrieval-augmented generation context for chat responses." /></>}
          checked={form.enabled ?? false}
          onChange={(e) => setForm({ ...form, enabled: e.target.checked })}
          className="mb-3"
        />

        <Row className="g-3">
          <Col md={8}>
            <Form.Group>
              <Form.Label className="small fw-medium">RAG URL</Form.Label>
              <Form.Control
                size="sm"
                value={form.url ?? ''}
                onChange={(e) => setForm({ ...form, url: e.target.value || null })}
                placeholder="http://localhost:9621"
              />
            </Form.Group>
          </Col>
          <Col md={4}>
            <Form.Group>
              <Form.Label className="small fw-medium">Query Mode</Form.Label>
              <Form.Select
                size="sm"
                value={form.queryMode ?? 'hybrid'}
                onChange={(e) => setForm({ ...form, queryMode: e.target.value })}
              >
                <option value="hybrid">hybrid</option>
                <option value="local">local</option>
                <option value="global">global</option>
                <option value="naive">naive</option>
              </Form.Select>
            </Form.Group>
          </Col>
          <Col md={6}>
            <Form.Group>
              <Form.Label className="small fw-medium">Timeout Seconds</Form.Label>
              <Form.Control
                size="sm"
                type="number"
                min={1}
                max={120}
                value={form.timeoutSeconds ?? 10}
                onChange={(e) => setForm({ ...form, timeoutSeconds: Number(e.target.value) })}
              />
            </Form.Group>
          </Col>
          <Col md={6}>
            <Form.Group>
              <Form.Label className="small fw-medium">Index Min Length</Form.Label>
              <Form.Control
                size="sm"
                type="number"
                min={1}
                max={2000}
                value={form.indexMinLength ?? 50}
                onChange={(e) => setForm({ ...form, indexMinLength: Number(e.target.value) })}
              />
            </Form.Group>
          </Col>
          <Col md={12}>
            <Form.Group>
              <Form.Label className="small fw-medium">API Key (optional)</Form.Label>
              <InputGroup size="sm">
                <Form.Control
                  type={showApiKey ? 'text' : 'password'}
                  value={form.apiKey ?? ''}
                  onChange={(e) => setForm({ ...form, apiKey: e.target.value || null })}
                />
                <Button variant="secondary" onClick={() => setShowApiKey(!showApiKey)}>
                  {showApiKey ? 'Hide' : 'Show'}
                </Button>
              </InputGroup>
            </Form.Group>
          </Col>
        </Row>

        <div className="d-flex align-items-center gap-2 mt-3">
          <Button variant="primary" size="sm" onClick={handleSave} disabled={!isDirty || updateRag.isPending}>
            {updateRag.isPending ? 'Saving...' : 'Save'}
          </Button>
          <SaveStateHint isDirty={isDirty} />
        </div>
      </Card.Body>
    </Card>
  );
}

function McpTab({ config }: { config: McpConfig }) {
  const updateMcp = useUpdateMcp();
  const [form, setForm] = useState<McpConfig>({ ...config });
  const isDirty = useMemo(() => hasDiff(form, config), [form, config]);

  useEffect(() => {
    setForm({ ...config });
  }, [config]);

  const handleSave = async () => {
    await updateMcp.mutateAsync(form);
    toast.success('MCP settings saved');
  };

  return (
    <Card className="settings-card">
      <Card.Body>
        <Card.Title className="h6 mb-3">MCP (Model Context Protocol)</Card.Title>
        <Form.Check
          type="switch"
          label="Enable MCP"
          checked={form.enabled ?? true}
          onChange={(e) => setForm({ ...form, enabled: e.target.checked })}
          className="mb-3"
        />
        <Row className="g-3 mb-3">
          <Col md={6}>
            <Form.Group>
              <Form.Label className="small fw-medium">Default Startup Timeout (s)</Form.Label>
              <Form.Control
                size="sm"
                type="number"
                min={1}
                max={300}
                value={form.defaultStartupTimeout ?? 30}
                onChange={(e) => setForm({ ...form, defaultStartupTimeout: parseInt(e.target.value, 10) || null })}
              />
            </Form.Group>
          </Col>
          <Col md={6}>
            <Form.Group>
              <Form.Label className="small fw-medium">Default Idle Timeout (min)</Form.Label>
              <Form.Control
                size="sm"
                type="number"
                min={1}
                max={120}
                value={form.defaultIdleTimeout ?? 5}
                onChange={(e) => setForm({ ...form, defaultIdleTimeout: parseInt(e.target.value, 10) || null })}
              />
            </Form.Group>
          </Col>
        </Row>
        <div className="d-flex align-items-center gap-2">
          <Button variant="primary" size="sm" onClick={handleSave} disabled={!isDirty || updateMcp.isPending}>
            {updateMcp.isPending ? 'Saving...' : 'Save'}
          </Button>
          <SaveStateHint isDirty={isDirty} />
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
  const [deleteMappingIdx, setDeleteMappingIdx] = useState<number | null>(null);
  const isWebhooksDirty = useMemo(() => hasDiff(form, webhookConfig), [form, webhookConfig]);

  useEffect(() => {
    if (settings?.webhooks) {setForm(settings.webhooks);}
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
    if (editIdx === idx) {setEditIdx(null);}
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
                  <td>{m.name || <em className="text-body-secondary">unnamed</em>}</td>
                  <td><Badge bg={m.action === 'agent' ? 'primary' : 'secondary'}>{m.action}</Badge></td>
                  <td className="small">{m.authMode}</td>
                  <td className="text-end">
                    <Button size="sm" variant="secondary" className="me-2"
                      onClick={() => setEditIdx(editIdx === idx ? null : idx)}>
                      {editIdx === idx ? 'Close' : 'Edit'}
                    </Button>
                    <Button size="sm" variant="danger" onClick={() => setDeleteMappingIdx(idx)}>Delete</Button>
                  </td>
                </tr>
              ))}
            </tbody>
          </Table>
        ) : (
          <p className="text-body-secondary small">No webhook mappings configured</p>
        )}

        {editIdx !== null && form.mappings[editIdx] && (
          <Card className="mb-3 webhook-editor-card border">
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

        <div className="d-flex align-items-center gap-2">
          <Button variant="primary" size="sm" onClick={handleSave} disabled={!isWebhooksDirty || updateWebhooks.isPending}>
            {updateWebhooks.isPending ? 'Saving...' : 'Save'}
          </Button>
          <SaveStateHint isDirty={isWebhooksDirty} />
        </div>
      </Card.Body>

      <ConfirmModal
        show={deleteMappingIdx !== null}
        title="Delete Webhook Mapping"
        message="This mapping will be removed permanently from runtime settings. This action cannot be undone."
        confirmLabel="Delete"
        confirmVariant="danger"
        onConfirm={() => {
          if (deleteMappingIdx !== null) {
            removeMapping(deleteMappingIdx);
            setDeleteMappingIdx(null);
          }
        }}
        onCancel={() => setDeleteMappingIdx(null)}
      />
    </Card>
  );
}

// ==================== Auto Mode Tab ====================

function AutoModeTab({ config }: { config: AutoModeConfig }) {
  const updateAuto = useUpdateAuto();
  const [form, setForm] = useState<AutoModeConfig>({ ...config });
  const isAutoDirty = useMemo(() => hasDiff(form, config), [form, config]);

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

        <div className="d-flex align-items-center gap-2">
          <Button variant="primary" size="sm" onClick={handleSave} disabled={!isAutoDirty || updateAuto.isPending}>
            {updateAuto.isPending ? 'Saving...' : 'Save'}
          </Button>
          <SaveStateHint isDirty={isAutoDirty} />
        </div>
      </Card.Body>
    </Card>
  );
}

// ==================== Advanced Tab ====================

type AdvancedMode = 'all' | 'rateLimit' | 'security' | 'compaction';

function AdvancedTab({ rateLimit, security, compaction, mode = 'all' }: {
  rateLimit: RateLimitConfig; security: SecurityConfig; compaction: CompactionConfig; mode?: AdvancedMode;
}) {
  const updateAdvanced = useUpdateAdvanced();
  const [rl, setRl] = useState<RateLimitConfig>({ ...rateLimit });
  const [sec, setSec] = useState<SecurityConfig>({ ...security });
  const [comp, setComp] = useState<CompactionConfig>({ ...compaction });
  const isAdvancedDirty = useMemo(
    () => hasDiff(rl, rateLimit) || hasDiff(sec, security) || hasDiff(comp, compaction),
    [rl, rateLimit, sec, security, comp, compaction],
  );

  useEffect(() => { setRl({ ...rateLimit }); }, [rateLimit]);
  useEffect(() => { setSec({ ...security }); }, [security]);
  useEffect(() => { setComp({ ...compaction }); }, [compaction]);

  const handleSave = async () => {
    await updateAdvanced.mutateAsync({ rateLimit: rl, security: sec, compaction: comp });
    toast.success('Advanced settings saved');
  };

  const showRateLimit = mode === 'all' || mode === 'rateLimit';
  const showSecurity = mode === 'all' || mode === 'security';
  const showCompaction = mode === 'all' || mode === 'compaction';

  return (
    <>
      <Row className="g-3 mb-3">
        {showRateLimit && <Col lg={4}>
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
        </Col>}

        {showCompaction && <Col lg={4}>
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
        </Col>}

        {showSecurity && <Col lg={4}>
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
              <Form.Check type="switch"
                label={<>Enable allowlist gate <Tip text="If disabled, allowlist checks are bypassed." /></>}
                checked={sec.allowlistEnabled ?? true}
                onChange={(e) => setSec({ ...sec, allowlistEnabled: e.target.checked })} className="mb-2" />
              <Form.Check type="switch"
                label={<>Tool confirmation <Tip text="Require user confirmation for destructive tool actions." /></>}
                checked={sec.toolConfirmationEnabled ?? false}
                onChange={(e) => setSec({ ...sec, toolConfirmationEnabled: e.target.checked })} className="mb-3" />
              <Form.Group>
                <Form.Label className="small fw-medium">
                  Max Input Length <Tip text="Maximum allowed characters per user message" />
                </Form.Label>
                <Form.Control size="sm" type="number" value={sec.maxInputLength ?? 10000}
                  onChange={(e) => setSec({ ...sec, maxInputLength: parseInt(e.target.value) || null })} />
              </Form.Group>
              <Form.Group className="mt-2">
                <Form.Label className="small fw-medium">
                  Tool Confirmation Timeout (seconds)
                </Form.Label>
                <Form.Control
                  size="sm"
                  type="number"
                  min={5}
                  max={600}
                  value={sec.toolConfirmationTimeoutSeconds ?? 60}
                  onChange={(e) => setSec({ ...sec, toolConfirmationTimeoutSeconds: parseInt(e.target.value, 10) || null })}
                />
              </Form.Group>
            </Card.Body>
          </Card>
        </Col>}
      </Row>

      <div className="d-flex align-items-center gap-2">
        <Button variant="primary" size="sm" onClick={handleSave} disabled={!isAdvancedDirty || updateAdvanced.isPending}>
          {updateAdvanced.isPending ? 'Saving...' : 'Save All'}
        </Button>
        <SaveStateHint isDirty={isAdvancedDirty} />
      </div>
    </>
  );
}
