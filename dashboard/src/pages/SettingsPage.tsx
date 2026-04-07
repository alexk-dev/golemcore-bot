import { useDeferredValue, useEffect, useState, type ReactElement } from 'react';
import {
  Badge, Card, Button, Row, Col, Spinner, Placeholder, Form, InputGroup,
} from 'react-bootstrap';
import { FiPackage, FiSearch, FiX } from 'react-icons/fi';
import { useNavigate, useParams } from 'react-router-dom';
import {
  useSettings, useRuntimeConfig, useUpdateRuntimeConfig,
} from '../hooks/useSettings';
import { usePluginMarketplace, usePluginSettingsCatalog } from '../hooks/usePlugins';
import { useMe } from '../hooks/useAuth';
import { useQueryClient } from '@tanstack/react-query';
import GeneralTab from './settings/GeneralTab';
import { AdvancedTab } from './settings/AdvancedTab';
import ToolsTab from './settings/ToolsTab';
import ModelsTab from './settings/ModelsTab';
import { ModelCatalogTab } from './settings/ModelCatalogTab';
import LlmProvidersTab from './settings/LlmProvidersTab';
import VoiceRoutingTab from './settings/VoiceRoutingTab';
import MemoryTab from './settings/MemoryTab';
import SkillsTab from './settings/SkillsTab';
import TurnTab from './settings/TurnTab';
import UsageTab from './settings/UsageTab';
import TelemetryTab from './settings/TelemetryTab';
import McpTab from './settings/McpTab';
import HiveTab from './settings/HiveTab';
import AutoModeTab from './settings/AutoModeTab';
import PlanModeTab from './settings/PlanModeTab';
import SelfEvolvingTab from './settings/SelfEvolvingTab';
import TracingTab from './settings/TracingTab';
import { UpdatesTab } from './settings/UpdatesTab';
import PluginSettingsPanel from './settings/PluginSettingsPanel';
import PluginsMarketplaceTab from './settings/PluginsMarketplaceTab';
import {
  useInstallSelfEvolvingTacticEmbeddingModel,
  useSelfEvolvingTacticSearchStatus,
} from '../hooks/useSelfEvolving';
import type { SelfEvolvingTacticSearchStatusPreview } from '../api/selfEvolving';
import {
  SETTINGS_BLOCKS,
  SETTINGS_SECTIONS,
  isSettingsSectionKey,
  type SettingsSectionMeta,
} from './settings/settingsCatalog';
import { filterCatalogBlocks } from './settings/settingsCatalogSearch';
import { useTelemetry } from '../lib/telemetry/TelemetryProvider';

interface CatalogCardItem {
  key: string;
  routeKey: string;
  title: string;
  description: string;
  icon: SettingsSectionMeta['icon'];
  badgeLabel?: string;
  badgeVariant?: string;
  metaText?: string;
}

interface CatalogBlockView {
  key: string;
  title: string;
  description: string;
  items: CatalogCardItem[];
}

interface CatalogBadgeMeta {
  label: string;
  variant: string;
  meta: string;
}

function buildMarketplaceBadge(
  pluginMarketplace: ReturnType<typeof usePluginMarketplace>['data'],
): CatalogBadgeMeta | null {
  if (pluginMarketplace == null) {
    return null;
  }

  const installedCount = pluginMarketplace.items.filter((item) => item.installed).length;
  const updatesCount = pluginMarketplace.items.filter((item) => item.updateAvailable).length;
  const installedMeta = `${installedCount} installed plugin${installedCount === 1 ? '' : 's'}`;

  if (!pluginMarketplace.available) {
    return {
      label: 'Unavailable',
      variant: 'secondary',
      meta: pluginMarketplace.message ?? 'Marketplace metadata is not available.',
    };
  }

  if (updatesCount > 0) {
    return {
      label: `${updatesCount} update${updatesCount === 1 ? '' : 's'}`,
      variant: 'warning',
      meta: installedMeta,
    };
  }

  return {
    label: `${pluginMarketplace.items.length} plugins`,
    variant: 'secondary',
    meta: installedMeta,
  };
}

// ==================== Main ====================

export default function SettingsPage(): ReactElement {
  const navigate = useNavigate();
  const { section } = useParams<{ section?: string }>();
  const [catalogSearch, setCatalogSearch] = useState('');
  const deferredCatalogSearch = useDeferredValue(catalogSearch);
  const { data: settings, isLoading: settingsLoading } = useSettings();
  const { data: rc, isLoading: rcLoading } = useRuntimeConfig();
  const updateRuntimeConfig = useUpdateRuntimeConfig();
  const { data: pluginCatalog = [], isLoading: pluginCatalogLoading } = usePluginSettingsCatalog();
  const { data: pluginMarketplace } = usePluginMarketplace();
  const { data: me } = useMe();
  const telemetry = useTelemetry();
  const qc = useQueryClient();
  const marketplaceBadge = buildMarketplaceBadge(pluginMarketplace);
  const installTacticEmbeddingModel = useInstallSelfEvolvingTacticEmbeddingModel();
  const [selfEvolvingTacticStatusPreview, setSelfEvolvingTacticStatusPreview] =
    useState<SelfEvolvingTacticSearchStatusPreview | null>(null);

  const staticSection = isSettingsSectionKey(section) ? section : null;
  const { data: selfEvolvingTacticSearchStatus } = useSelfEvolvingTacticSearchStatus(
    staticSection === 'self-evolving',
    selfEvolvingTacticStatusPreview,
  );
  const pluginSection = staticSection == null && section != null
    ? pluginCatalog.find((item) => item.routeKey === section) ?? null
    : null;

  const sectionMeta = staticSection != null
    ? SETTINGS_SECTIONS.find((entry) => entry.key === staticSection) ?? null
    : pluginSection != null
      ? {
        key: pluginSection.routeKey,
        title: pluginSection.title,
        description: pluginSection.description,
        icon: FiPackage,
      }
      : null;
  const selectedSectionKey = staticSection ?? pluginSection?.routeKey ?? 'catalog';

  const catalogBlocks: CatalogBlockView[] = (() => {
    const byKey = new Map<string, CatalogBlockView>();

    SETTINGS_BLOCKS.forEach((block) => {
      const items = block.sections.flatMap((sectionKey) => {
        const entry = SETTINGS_SECTIONS.find((candidate) => candidate.key === sectionKey);
        if (entry == null) {
          return [];
        }

        return {
          key: entry.key,
          routeKey: entry.key,
          title: entry.title,
          description: entry.description,
          icon: entry.icon,
          badgeLabel: entry.key === 'plugins-marketplace' ? marketplaceBadge?.label : undefined,
          badgeVariant: entry.key === 'plugins-marketplace' ? marketplaceBadge?.variant : undefined,
          metaText: entry.key === 'plugins-marketplace' ? marketplaceBadge?.meta : undefined,
        };
      });
      byKey.set(block.key, {
        key: block.key,
        title: block.title,
        description: block.description,
        items,
      });
    });

    pluginCatalog
      .slice()
      .sort((left, right) => {
        const leftOrder = left.order ?? Number.MAX_SAFE_INTEGER;
        const rightOrder = right.order ?? Number.MAX_SAFE_INTEGER;
        if (leftOrder !== rightOrder) {
          return leftOrder - rightOrder;
        }
        return left.title.localeCompare(right.title);
      })
      .forEach((item) => {
        const blockKey = item.blockKey ?? 'plugins';
        const current = byKey.get(blockKey) ?? {
          key: blockKey,
          title: item.blockTitle ?? 'Plugins',
          description: item.blockDescription ?? 'Plugin-provided settings',
          items: [],
        };
        current.items.push({
          key: item.routeKey,
          routeKey: item.routeKey,
          title: item.title,
          description: item.description,
          icon: FiPackage,
        });
        byKey.set(blockKey, current);
      });

    return Array.from(byKey.values()).filter((block) => block.items.length > 0);
  })();
  const filteredCatalogBlocks = filterCatalogBlocks(catalogBlocks, deferredCatalogSearch);

  useEffect(() => {
    telemetry.recordCounter('settings_open_count');
  // Intentionally record one open per SettingsPage mount.
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    telemetry.recordKeyedCounter('settings_section_views_by_key', selectedSectionKey);
  }, [selectedSectionKey, telemetry]);

  if (settingsLoading || rcLoading || pluginCatalogLoading) {
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

  if (staticSection == null && pluginSection == null) {
    return (
      <div>
        <div className="page-header">
          <h4>Settings</h4>
          <p className="text-body-secondary mb-0">Select a settings category</p>
        </div>
        <Card className="settings-card mb-4">
          <Card.Body>
            <Form.Group controlId="settings-catalog-search" className="mb-0">
              <Form.Label className="small fw-medium">Search settings</Form.Label>
              <InputGroup>
                <InputGroup.Text aria-hidden="true"><FiSearch size={16} /></InputGroup.Text>
                <Form.Control
                  type="search"
                  placeholder="Search by name"
                  value={catalogSearch}
                  onChange={(event) => {
                    telemetry.recordCounter('settings_search_count');
                    setCatalogSearch(event.target.value);
                  }}
                />
                {catalogSearch.trim().length > 0 && (
                  <Button type="button" variant="outline-secondary" onClick={() => setCatalogSearch('')}>
                    <FiX size={16} className="me-1" />
                    Clear
                  </Button>
                )}
              </InputGroup>
              <Form.Text className="text-muted">
                Start typing to quickly find the setting you need.
              </Form.Text>
            </Form.Group>
          </Card.Body>
        </Card>

        {filteredCatalogBlocks.length === 0 ? (
          <Card className="settings-card">
            <Card.Body>
              <h2 className="h6 mb-2">Nothing found</h2>
              <p className="text-body-secondary small mb-0">
                No settings match `{catalogSearch}`. Try another name or clear the search.
              </p>
            </Card.Body>
          </Card>
        ) : filteredCatalogBlocks.map((block) => (
          <div key={block.key} className="mb-4">
            <div className="mb-2">
              <h2 className="h6 mb-1">{block.title}</h2>
              <p className="text-body-secondary small mb-0">{block.description}</p>
            </div>
            <Row className="g-3">
              {block.items.map((item) => (
                <Col sm={6} lg={4} xl={3} key={item.key}>
                  <Card className="settings-card h-100">
                    <Card.Body className="d-flex flex-column">
                      <div className="d-flex align-items-start justify-content-between gap-2 mb-2">
                        <h3 className="h6 mb-0 settings-catalog-title">
                          <span className="text-primary"><item.icon size={18} /></span>
                          <span>{item.title}</span>
                        </h3>
                        {item.badgeLabel != null && item.badgeVariant != null && (
                          <Badge bg={item.badgeVariant}>{item.badgeLabel}</Badge>
                        )}
                      </div>
                      <Card.Text className="text-body-secondary small mb-3">{item.description}</Card.Text>
                      {item.metaText != null && (
                        <div className="small text-body-secondary mb-3">{item.metaText}</div>
                      )}
                      <div className="mt-auto">
                        <Button type="button" size="sm" variant="primary" onClick={() => navigate(`/settings/${item.routeKey}`)}>
                          Open
                        </Button>
                      </div>
                    </Card.Body>
                  </Card>
                </Col>
              ))}
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

      {pluginSection != null && <PluginSettingsPanel routeKey={pluginSection.routeKey} />}

      {staticSection === 'general' && <GeneralTab settings={settings} me={me} qc={qc} />}
      {staticSection === 'models' && rc != null && <ModelsTab config={rc.modelRouter} llmConfig={rc.llm} />}
      {staticSection === 'llm-providers' && rc != null && <LlmProvidersTab config={rc.llm} modelRouter={rc.modelRouter} />}
      {staticSection === 'model-catalog' && rc != null && (
        <ModelCatalogTab
          llmConfig={rc.llm}
          modelRegistryConfig={rc.modelRegistry}
          isSavingModelRegistry={updateRuntimeConfig.isPending}
          onSaveModelRegistry={async (modelRegistryConfig) => {
            await updateRuntimeConfig.mutateAsync({
              ...rc,
              modelRegistry: modelRegistryConfig,
            });
          }}
        />
      )}
      {staticSection === 'plugins-marketplace' && <PluginsMarketplaceTab />}

      {staticSection === 'tool-filesystem' && rc != null && <ToolsTab config={rc.tools} mode="filesystem" />}
      {staticSection === 'tool-shell' && rc != null && <ToolsTab config={rc.tools} mode="shell" />}
      {staticSection === 'tool-automation' && rc != null && <ToolsTab config={rc.tools} mode="automation" />}
      {staticSection === 'tool-goals' && rc != null && <ToolsTab config={rc.tools} mode="goals" />}
      {staticSection === 'tool-voice' && rc != null && <VoiceRoutingTab config={rc.voice} />}

      {staticSection === 'memory' && rc != null && <MemoryTab config={rc.memory} />}
      {staticSection === 'skills' && rc != null && <SkillsTab config={rc.skills} />}
      {staticSection === 'turn' && rc != null && <TurnTab config={rc.turn} />}
      {staticSection === 'usage' && rc != null && <UsageTab config={rc.usage} />}
      {staticSection === 'telemetry' && rc != null && <TelemetryTab config={rc.telemetry ?? { enabled: false }} />}
      {staticSection === 'mcp' && rc != null && <McpTab config={rc.mcp} />}
      {staticSection === 'hive' && rc != null && <HiveTab config={rc.hive} />}
      {staticSection === 'self-evolving' && rc != null && (
        <SelfEvolvingTab
          config={rc.selfEvolving}
          tacticSearchStatus={selfEvolvingTacticSearchStatus ?? null}
          isInstallingTacticEmbedding={installTacticEmbeddingModel.isPending}
          onTacticSearchStatusPreviewChange={setSelfEvolvingTacticStatusPreview}
          onInstallTacticEmbedding={async (model) => {
            await installTacticEmbeddingModel.mutateAsync(model);
          }}
          isSaving={updateRuntimeConfig.isPending}
          onSave={async (selfEvolving) => {
            await updateRuntimeConfig.mutateAsync({
              ...rc,
              selfEvolving,
            });
          }}
        />
      )}
      {staticSection === 'plan' && rc != null && <PlanModeTab config={rc.plan} />}
      {staticSection === 'auto' && rc != null && <AutoModeTab config={rc.autoMode} />}
      {staticSection === 'tracing' && rc != null && <TracingTab config={rc.tracing} />}
      {staticSection === 'updates' && <UpdatesTab />}

      {staticSection === 'advanced-rate-limit' && rc != null && (
        <AdvancedTab rateLimit={rc.rateLimit} security={rc.security} compaction={rc.compaction} mode="rateLimit" />
      )}
      {staticSection === 'advanced-security' && rc != null && (
        <AdvancedTab rateLimit={rc.rateLimit} security={rc.security} compaction={rc.compaction} mode="security" />
      )}
      {staticSection === 'advanced-compaction' && rc != null && (
        <AdvancedTab rateLimit={rc.rateLimit} security={rc.security} compaction={rc.compaction} mode="compaction" />
      )}
    </div>
  );
}
