import { type ReactElement, useState, useEffect } from 'react';
import {
  Card, Button, Row, Col, Spinner, Placeholder,
} from 'react-bootstrap';
import { useNavigate, useParams } from 'react-router-dom';
import {
  useSettings, useRuntimeConfig,
} from '../hooks/useSettings';
import { useMe } from '../hooks/useAuth';
import { useQueryClient } from '@tanstack/react-query';
import {
  FiSliders, FiSend, FiCpu, FiTool, FiMic,
  FiGlobe, FiPlayCircle, FiShield, FiSearch, FiHardDrive, FiBarChart2,
  FiTerminal, FiMail, FiCompass, FiShuffle, FiKey,
} from 'react-icons/fi';
import GeneralTab from './settings/GeneralTab';
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
import McpTab from './settings/McpTab';
import AutoModeTab from './settings/AutoModeTab';

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
