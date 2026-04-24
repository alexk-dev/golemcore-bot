import { useDeferredValue, useEffect, useState, type ReactElement } from 'react';
import { Card, Button, Spinner, Placeholder } from '../components/ui/tailwind-components';
import { useNavigate, useParams } from 'react-router-dom';
import {
  useSettings, useRuntimeConfig, useUpdateRuntimeConfig,
} from '../hooks/useSettings';
import { useHiveStatus } from '../hooks/useHive';
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
  isSettingsSectionKey,
} from './settings/settingsCatalog';
import { filterCatalogBlocks } from './settings/settingsCatalogSearch';
import { SettingsCatalogView, type CatalogBlockView } from './settings/SettingsCatalogView';
import { buildCatalogBlocks, buildMarketplaceBadge, resolveSectionMeta } from './settings/SettingsPageState';
import { useTelemetry } from '../lib/telemetry/TelemetryContext';

// ==================== Main ====================

export default function SettingsPage(): ReactElement {
  const navigate = useNavigate();
  const { section } = useParams<{ section?: string }>();
  const [catalogSearch, setCatalogSearch] = useState('');
  const deferredCatalogSearch = useDeferredValue(catalogSearch);
  const { data: settings, isLoading: settingsLoading } = useSettings();
  const { data: rc, isLoading: rcLoading } = useRuntimeConfig();
  const hiveStatus = useHiveStatus();
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

  const sectionMeta = resolveSectionMeta(staticSection, pluginSection);
  const selectedSectionKey = staticSection ?? pluginSection?.routeKey ?? 'catalog';

  const catalogBlocks: CatalogBlockView[] = buildCatalogBlocks(pluginCatalog, marketplaceBadge);
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
      <SettingsCatalogView
        catalogSearch={catalogSearch}
        filteredCatalogBlocks={filteredCatalogBlocks}
        onSearchChange={(value) => {
          telemetry.recordCounter('settings_search_count');
          setCatalogSearch(value);
        }}
        onClearSearch={() => setCatalogSearch('')}
        onOpenSection={(routeKey) => navigate(`/settings/${routeKey}`)}
      />
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
      {staticSection === 'models' && rc != null && (
        <ModelsTab config={rc.modelRouter} llmConfig={rc.llm} hiveStatus={hiveStatus.data} />
      )}
      {staticSection === 'llm-providers' && rc != null && (
        <LlmProvidersTab config={rc.llm} modelRouter={rc.modelRouter} hiveStatus={hiveStatus.data} />
      )}
      {staticSection === 'model-catalog' && rc != null && (
        <ModelCatalogTab
          llmConfig={rc.llm}
          modelRegistryConfig={rc.modelRegistry}
          isSavingModelRegistry={updateRuntimeConfig.isPending}
          hiveStatus={hiveStatus.data}
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
      {staticSection === 'usage' && rc != null && <UsageTab config={rc.usage} sessionRetention={rc.sessionRetention} />}
      {staticSection === 'telemetry' && rc != null && <TelemetryTab config={rc.telemetry ?? { enabled: true }} />}
      {staticSection === 'mcp' && rc != null && <McpTab config={rc.mcp} />}
      {staticSection === 'hive' && rc != null && <HiveTab config={rc.hive} hiveStatus={hiveStatus.data} />}
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
      {staticSection === 'auto' && rc != null && <AutoModeTab config={rc.autoMode} />}
      {staticSection === 'plan' && rc != null && <PlanModeTab config={rc.plan} />}
      {staticSection === 'tracing' && rc != null && <TracingTab config={rc.tracing} />}
      {staticSection === 'updates' && <UpdatesTab />}

      {staticSection === 'advanced-rate-limit' && rc != null && (
        <AdvancedTab rateLimit={rc.rateLimit} security={rc.security} compaction={rc.compaction} resilience={rc.resilience} mode="rateLimit" />
      )}
      {staticSection === 'advanced-security' && rc != null && (
        <AdvancedTab rateLimit={rc.rateLimit} security={rc.security} compaction={rc.compaction} resilience={rc.resilience} mode="security" />
      )}
      {staticSection === 'advanced-compaction' && rc != null && (
        <AdvancedTab rateLimit={rc.rateLimit} security={rc.security} compaction={rc.compaction} resilience={rc.resilience} mode="compaction" />
      )}
      {staticSection === 'advanced-resilience' && rc != null && (
        <AdvancedTab rateLimit={rc.rateLimit} security={rc.security} compaction={rc.compaction} resilience={rc.resilience} mode="resilience" />
      )}
    </div>
  );
}
