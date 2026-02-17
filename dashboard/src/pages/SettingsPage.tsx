import { type ReactElement, useState, useEffect, useMemo } from 'react';
import {
  Card, Form, Button, Row, Col, Spinner,
  OverlayTrigger, Tooltip, Placeholder,
} from 'react-bootstrap';
import { useNavigate, useParams } from 'react-router-dom';
import {
  useSettings, useUpdatePreferences, useRuntimeConfig,
  useUpdateAuto,
  useUpdateMcp,
} from '../hooks/useSettings';
import { useMe } from '../hooks/useAuth';
import { changePassword } from '../api/auth';
import MfaSetup from '../components/auth/MfaSetup';
import toast from 'react-hot-toast';
import { type QueryClient, useQueryClient } from '@tanstack/react-query';
import type {
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
import LlmProvidersTab from './settings/LlmProvidersTab';
import VoiceTab from './settings/VoiceTab';
import MemoryTab from './settings/MemoryTab';
import SkillsTab from './settings/SkillsTab';
import TurnTab from './settings/TurnTab';
import UsageTab from './settings/UsageTab';
import RagTab from './settings/RagTab';

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
