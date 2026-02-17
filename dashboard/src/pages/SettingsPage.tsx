import { type ReactElement, useState, useEffect, useMemo } from 'react';
import {
  Card, Form, Button, Row, Col, Spinner,
  Badge, InputGroup, OverlayTrigger, Tooltip, Placeholder,
} from 'react-bootstrap';
import { useNavigate, useParams } from 'react-router-dom';
import {
  useSettings, useUpdatePreferences, useRuntimeConfig,
  useUpdateLlm,
  useUpdateVoice, useUpdateMemory, useUpdateSkills,
  useUpdateTurn,
  useUpdateAuto,
  useUpdateUsage,
  useUpdateRag,
  useUpdateMcp,
} from '../hooks/useSettings';
import { useMe } from '../hooks/useAuth';
import { changePassword } from '../api/auth';
import MfaSetup from '../components/auth/MfaSetup';
import toast from 'react-hot-toast';
import { type QueryClient, useQueryClient } from '@tanstack/react-query';
import type {
  ModelRouterConfig, LlmConfig, VoiceConfig,
  MemoryConfig, SkillsConfig,
  TurnConfig,
  UsageConfig,
  RagConfig,
  McpConfig,
  AutoModeConfig,
} from '../api/settings';
import {
  FiHelpCircle, FiSliders, FiSend, FiCpu, FiTool, FiMic,
  FiGlobe, FiPlayCircle, FiShield, FiSearch, FiHardDrive, FiBarChart2,
  FiTerminal, FiMail, FiCompass, FiShuffle, FiKey,
} from 'react-icons/fi';
import WebhooksTab from './settings/WebhooksTab';
import { AdvancedTab } from './settings/AdvancedTab';
import ToolsTab from './settings/ToolsTab';
import TelegramTab from './settings/TelegramTab';
import ModelsTab from './settings/ModelsTab';

// ==================== Tooltip Helper ====================

function Tip({ text }: { text: string }): ReactElement {
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

function SaveStateHint({ isDirty }: { isDirty: boolean }): ReactElement {
  return (
    <small className="text-body-secondary">
      {isDirty ? 'Unsaved changes' : 'All changes saved'}
    </small>
  );
}

function toNullableString(value: string): string | null {
  return value.length > 0 ? value : null;
}

function toNullableInt(value: string): number | null {
  const parsed = parseInt(value, 10);
  return Number.isNaN(parsed) ? null : parsed;
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

interface GeneralSettingsData {
  language?: string;
  timezone?: string;
}

interface AuthMe {
  mfaEnabled?: boolean;
}

interface GeneralTabProps {
  settings: GeneralSettingsData | undefined;
  me: AuthMe | undefined;
  qc: QueryClient;
}

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

export default function SettingsPage(): ReactElement {
  const navigate = useNavigate();
  const { section } = useParams<{ section?: string }>();
  const { data: settings, isLoading: settingsLoading } = useSettings();
  const { data: rc, isLoading: rcLoading } = useRuntimeConfig();
  const { data: me } = useMe();
  const qc = useQueryClient();

  const selectedSection = isSettingsSectionKey(section) ? section : null;

  const sectionMeta = selectedSection != null
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

  if (selectedSection == null) {
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
                if (item == null) {
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
      {selectedSection === 'telegram' && rc != null && <TelegramTab config={rc.telegram} voiceConfig={rc.voice} />}
      {selectedSection === 'models' && rc != null && <ModelsTab config={rc.modelRouter} llmConfig={rc.llm} />}
      {selectedSection === 'llm-providers' && rc != null && <LlmProvidersTab config={rc.llm} modelRouter={rc.modelRouter} />}

      {selectedSection === 'tool-browser' && rc != null && <ToolsTab config={rc.tools} mode="browser" />}
      {selectedSection === 'tool-brave' && rc != null && <ToolsTab config={rc.tools} mode="brave" />}
      {selectedSection === 'tool-filesystem' && rc != null && <ToolsTab config={rc.tools} mode="filesystem" />}
      {selectedSection === 'tool-shell' && rc != null && <ToolsTab config={rc.tools} mode="shell" />}
      {selectedSection === 'tool-email' && rc != null && <ToolsTab config={rc.tools} mode="email" />}
      {selectedSection === 'tool-automation' && rc != null && <ToolsTab config={rc.tools} mode="automation" />}
      {selectedSection === 'tool-goals' && rc != null && <ToolsTab config={rc.tools} mode="goals" />}

      {selectedSection === 'voice-elevenlabs' && rc != null && <VoiceTab config={rc.voice} />}
      {selectedSection === 'memory' && rc != null && <MemoryTab config={rc.memory} />}
      {selectedSection === 'skills' && rc != null && <SkillsTab config={rc.skills} />}
      {selectedSection === 'turn' && rc != null && <TurnTab config={rc.turn} />}
      {selectedSection === 'usage' && rc != null && <UsageTab config={rc.usage} />}
      {selectedSection === 'rag' && rc != null && <RagTab config={rc.rag} />}
      {selectedSection === 'mcp' && rc != null && <McpTab config={rc.mcp} />}
      {selectedSection === 'webhooks' && <WebhooksTab />}
      {selectedSection === 'auto' && rc != null && <AutoModeTab config={rc.autoMode} />}

      {selectedSection === 'advanced-rate-limit' && rc != null && (
        <AdvancedTab rateLimit={rc.rateLimit} security={rc.security} compaction={rc.compaction} mode="rateLimit" />
      )}
      {selectedSection === 'advanced-security' && rc != null && (
        <AdvancedTab rateLimit={rc.rateLimit} security={rc.security} compaction={rc.compaction} mode="security" />
      )}
      {selectedSection === 'advanced-compaction' && rc != null && (
        <AdvancedTab rateLimit={rc.rateLimit} security={rc.security} compaction={rc.compaction} mode="compaction" />
      )}
    </div>
  );
}

// ==================== General Tab ====================

function GeneralTab({ settings, me, qc }: GeneralTabProps): ReactElement {
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
    } else {
      toast.error('Failed to change password');
    }
  };

  const handleChangePasswordSubmit = (e: React.FormEvent): void => {
    void handleChangePassword(e);
  };

  return (
    <Row className="g-3">
      <Col lg={6}>
        <Card className="settings-card">
          <Card.Body>
            <Card.Title className="h6 mb-3">Preferences</Card.Title>
            <Form onSubmit={handleSavePrefsSubmit}>
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
          onUpdate={() => { void qc.invalidateQueries({ queryKey: ['auth', 'me'] }); }}
        />
        <Card className="settings-card mt-3">
          <Card.Body>
            <Card.Title className="h6 mb-3">Change Password</Card.Title>
            <Form onSubmit={handleChangePasswordSubmit}>
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

function LlmProvidersTab({ config, modelRouter }: { config: LlmConfig; modelRouter: ModelRouterConfig }): ReactElement {
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

  const addProvider = (): void => {
    const name = newProviderName.trim();
    if (name.length === 0) {
      return;
    }
    const normalizedName = name.toLowerCase();
    if (!/^[a-z0-9][a-z0-9_-]*$/.test(normalizedName)) {
      toast.error('Provider name must match [a-z0-9][a-z0-9_-]*');
      return;
    }
    if (Object.prototype.hasOwnProperty.call(form.providers, normalizedName)) {
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

  const handleSave = async (): Promise<void> => {
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
                      {form.providers[provider]?.apiKeyPresent === true && (
                        <Badge bg="success-subtle" text="success">Secret set</Badge>
                      )}
                      {(form.providers[provider]?.apiKey?.length ?? 0) > 0 && (
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
                         placeholder={form.providers[provider]?.apiKeyPresent === true
                           ? 'Secret is configured (hidden)'
                           : ''}
                        type={showKeys[provider] ? 'text' : 'password'}
                        value={form.providers[provider]?.apiKey ?? ''}
                         onChange={(e) => setForm({
                           ...form,
                           providers: {
                             ...form.providers,
                            [provider]: { ...form.providers[provider], apiKey: toNullableString(e.target.value) },
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
                          [provider]: { ...form.providers[provider], baseUrl: toNullableString(e.target.value) },
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
                             requestTimeoutSeconds: toNullableInt(e.target.value) ?? 300,
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
          <Button variant="primary" size="sm" onClick={() => { void handleSave(); }} disabled={!isDirty || updateLlm.isPending}>
            {updateLlm.isPending ? 'Saving...' : 'Save'}
          </Button>
          <SaveStateHint isDirty={isDirty} />
        </div>
      </Card.Body>
    </Card>
  );
}

// ==================== Voice Tab ====================

function VoiceTab({ config }: { config: VoiceConfig }): ReactElement {
  const updateVoice = useUpdateVoice();
  const [form, setForm] = useState<VoiceConfig>({ ...config });
  const [showKey, setShowKey] = useState(false);
  const isVoiceDirty = useMemo(() => hasDiff(form, config), [form, config]);

  useEffect(() => { setForm({ ...config }); }, [config]);

  const handleSave = async (): Promise<void> => {
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
              onChange={(e) => setForm({ ...form, apiKey: toNullableString(e.target.value) })} />
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
                onChange={(e) => setForm({ ...form, voiceId: toNullableString(e.target.value) })} />
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
                onChange={(e) => setForm({ ...form, ttsModelId: toNullableString(e.target.value) })}
                placeholder="eleven_multilingual_v2" />
            </Form.Group>
          </Col>
          <Col md={6}>
            <Form.Group>
              <Form.Label className="small fw-medium">
                STT Model <Tip text="Speech-to-text model for transcribing voice messages." />
              </Form.Label>
              <Form.Control size="sm" value={form.sttModelId ?? ''}
                onChange={(e) => setForm({ ...form, sttModelId: toNullableString(e.target.value) })}
                placeholder="scribe_v1" />
            </Form.Group>
          </Col>
        </Row>

        <div className="mt-3 d-flex align-items-center gap-2">
          <Button variant="primary" size="sm" onClick={() => { void handleSave(); }} disabled={!isVoiceDirty || updateVoice.isPending}>
            {updateVoice.isPending ? 'Saving...' : 'Save'}
          </Button>
          <SaveStateHint isDirty={isVoiceDirty} />
        </div>
      </Card.Body>
    </Card>
  );
}

function MemoryTab({ config }: { config: MemoryConfig }): ReactElement {
  const updateMemory = useUpdateMemory();
  const [form, setForm] = useState<MemoryConfig>({ ...config });
  const isDirty = useMemo(() => hasDiff(form, config), [form, config]);

  useEffect(() => {
    setForm({ ...config });
  }, [config]);

  const handleSave = async (): Promise<void> => {
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
            onChange={(e) => setForm({ ...form, recentDays: toNullableInt(e.target.value) })}
          />
        </Form.Group>
        <div className="d-flex align-items-center gap-2">
          <Button variant="primary" size="sm" onClick={() => { void handleSave(); }} disabled={!isDirty || updateMemory.isPending}>
            {updateMemory.isPending ? 'Saving...' : 'Save'}
          </Button>
          <SaveStateHint isDirty={isDirty} />
        </div>
      </Card.Body>
    </Card>
  );
}

function SkillsTab({ config }: { config: SkillsConfig }): ReactElement {
  const updateSkills = useUpdateSkills();
  const [form, setForm] = useState<SkillsConfig>({ ...config });
  const isDirty = useMemo(() => hasDiff(form, config), [form, config]);

  useEffect(() => {
    setForm({ ...config });
  }, [config]);

  const handleSave = async (): Promise<void> => {
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
          <Button variant="primary" size="sm" onClick={() => { void handleSave(); }} disabled={!isDirty || updateSkills.isPending}>
            {updateSkills.isPending ? 'Saving...' : 'Save'}
          </Button>
          <SaveStateHint isDirty={isDirty} />
        </div>
      </Card.Body>
    </Card>
  );
}

function TurnTab({ config }: { config: TurnConfig }): ReactElement {
  const updateTurn = useUpdateTurn();
  const [form, setForm] = useState<TurnConfig>({ ...config });
  const isDirty = useMemo(() => hasDiff(form, config), [form, config]);

  useEffect(() => {
    setForm({ ...config });
  }, [config]);

  const handleSave = async (): Promise<void> => {
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
                onChange={(e) => setForm({ ...form, maxLlmCalls: toNullableInt(e.target.value) })}
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
                onChange={(e) => setForm({ ...form, maxToolExecutions: toNullableInt(e.target.value) })}
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
                onChange={(e) => setForm({ ...form, deadline: toNullableString(e.target.value) })}
                placeholder="PT1H"
              />
            </Form.Group>
          </Col>
        </Row>
        <div className="d-flex align-items-center gap-2">
          <Button variant="primary" size="sm" onClick={() => { void handleSave(); }} disabled={!isDirty || updateTurn.isPending}>
            {updateTurn.isPending ? 'Saving...' : 'Save'}
          </Button>
          <SaveStateHint isDirty={isDirty} />
        </div>
      </Card.Body>
    </Card>
  );
}

function UsageTab({ config }: { config: UsageConfig }): ReactElement {
  const updateUsage = useUpdateUsage();
  const [form, setForm] = useState<UsageConfig>({ ...config });
  const isDirty = useMemo(() => hasDiff(form, config), [form, config]);

  useEffect(() => {
    setForm({ ...config });
  }, [config]);

  const handleSave = async (): Promise<void> => {
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
          <Button variant="primary" size="sm" onClick={() => { void handleSave(); }} disabled={!isDirty || updateUsage.isPending}>
            {updateUsage.isPending ? 'Saving...' : 'Save'}
          </Button>
          <SaveStateHint isDirty={isDirty} />
        </div>
      </Card.Body>
    </Card>
  );
}

function RagTab({ config }: { config: RagConfig }): ReactElement {
  const updateRag = useUpdateRag();
  const [form, setForm] = useState<RagConfig>({ ...config });
  const [showApiKey, setShowApiKey] = useState(false);
  const isDirty = useMemo(() => hasDiff(form, config), [form, config]);

  useEffect(() => {
    setForm({ ...config });
  }, [config]);

  const handleSave = async (): Promise<void> => {
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
                onChange={(e) => setForm({ ...form, url: toNullableString(e.target.value) })}
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
                  onChange={(e) => setForm({ ...form, apiKey: toNullableString(e.target.value) })}
                />
                <Button variant="secondary" onClick={() => setShowApiKey(!showApiKey)}>
                  {showApiKey ? 'Hide' : 'Show'}
                </Button>
              </InputGroup>
            </Form.Group>
          </Col>
        </Row>

        <div className="d-flex align-items-center gap-2 mt-3">
          <Button variant="primary" size="sm" onClick={() => { void handleSave(); }} disabled={!isDirty || updateRag.isPending}>
            {updateRag.isPending ? 'Saving...' : 'Save'}
          </Button>
          <SaveStateHint isDirty={isDirty} />
        </div>
      </Card.Body>
    </Card>
  );
}

function McpTab({ config }: { config: McpConfig }): ReactElement {
  const updateMcp = useUpdateMcp();
  const [form, setForm] = useState<McpConfig>({ ...config });
  const isDirty = useMemo(() => hasDiff(form, config), [form, config]);

  useEffect(() => {
    setForm({ ...config });
  }, [config]);

  const handleSave = async (): Promise<void> => {
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
                onChange={(e) => setForm({ ...form, defaultStartupTimeout: toNullableInt(e.target.value) })}
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
                onChange={(e) => setForm({ ...form, defaultIdleTimeout: toNullableInt(e.target.value) })}
              />
            </Form.Group>
          </Col>
        </Row>
        <div className="d-flex align-items-center gap-2">
          <Button variant="primary" size="sm" onClick={() => { void handleSave(); }} disabled={!isDirty || updateMcp.isPending}>
            {updateMcp.isPending ? 'Saving...' : 'Save'}
          </Button>
          <SaveStateHint isDirty={isDirty} />
        </div>
      </Card.Body>
    </Card>
  );
}

// ==================== Auto Mode Tab ====================

function AutoModeTab({ config }: { config: AutoModeConfig }): ReactElement {
  const updateAuto = useUpdateAuto();
  const [form, setForm] = useState<AutoModeConfig>({ ...config });
  const isAutoDirty = useMemo(() => hasDiff(form, config), [form, config]);

  useEffect(() => { setForm({ ...config }); }, [config]);

  const handleSave = async (): Promise<void> => {
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
                onChange={(e) => setForm({ ...form, taskTimeLimitMinutes: toNullableInt(e.target.value) })} />
            </Form.Group>
          </Col>
          <Col md={4}>
            <Form.Group>
              <Form.Label className="small fw-medium">
                Max Goals <Tip text="Maximum number of concurrent goals the bot can work on" />
              </Form.Label>
              <Form.Control size="sm" type="number" value={form.maxGoals ?? 3}
                onChange={(e) => setForm({ ...form, maxGoals: toNullableInt(e.target.value) })} />
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
          <Button variant="primary" size="sm" onClick={() => { void handleSave(); }} disabled={!isAutoDirty || updateAuto.isPending}>
            {updateAuto.isPending ? 'Saving...' : 'Save'}
          </Button>
          <SaveStateHint isDirty={isAutoDirty} />
        </div>
      </Card.Body>
    </Card>
  );
}
