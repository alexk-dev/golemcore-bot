import type { ReactElement } from 'react';
import {
  Card, Button, Row, Col, Spinner, Placeholder,
} from 'react-bootstrap';
import { useNavigate, useParams } from 'react-router-dom';
import {
  useSettings, useRuntimeConfig,
} from '../hooks/useSettings';
import { useMe } from '../hooks/useAuth';
import { useQueryClient } from '@tanstack/react-query';
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
import {
  SETTINGS_BLOCKS,
  SETTINGS_SECTIONS,
  isSettingsSectionKey,
} from './settings/settingsCatalog';

// ==================== Main ====================

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
              <h2 className="h6 mb-1">{block.title}</h2>
              <p className="text-body-secondary small mb-0">{block.description}</p>
            </div>
            <Row className="g-3">
              {block.sections.map((sectionKey) => {
                const item = SETTINGS_SECTIONS.find((section) => section.key === sectionKey);
                if (item == null) {
                  return null;
                }

                return (
                  <Col sm={6} lg={4} xl={3} key={item.key}>
                    <Card className="settings-card h-100">
                      <Card.Body className="d-flex flex-column">
                        <h3 className="h6 mb-2 settings-catalog-title">
                          <span className="text-primary"><item.icon size={18} /></span>
                          <span>{item.title}</span>
                        </h3>
                        <Card.Text className="text-body-secondary small mb-3">{item.description}</Card.Text>
                        <div className="mt-auto">
                          <Button type="button" size="sm" variant="primary" onClick={() => navigate(`/settings/${item.key}`)}>
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
        <Button type="button" variant="secondary" size="sm" onClick={() => navigate('/settings')}>
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
