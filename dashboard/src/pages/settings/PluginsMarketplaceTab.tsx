import { type ReactElement, useDeferredValue, useState } from 'react';
import {
  Alert,
  Badge,
  Button,
  Card,
  Col,
  Form,
  InputGroup,
  Row,
  Spinner,
} from 'react-bootstrap';
import toast from 'react-hot-toast';
import {
  FiCheckCircle,
  FiCloud,
  FiCpu,
  FiDownloadCloud,
  FiGlobe,
  FiMail,
  FiMessageSquare,
  FiMic,
  FiPackage,
  FiSearch,
  FiSettings,
  FiVolume2,
} from 'react-icons/fi';
import { useNavigate } from 'react-router-dom';
import type { IconType } from 'react-icons';
import type { PluginMarketplaceItem } from '../../api/plugins';
import SettingsCardTitle from '../../components/common/SettingsCardTitle';
import { useInstallPluginFromMarketplace, usePluginMarketplace } from '../../hooks/usePlugins';
import { extractErrorMessage } from '../../utils/extractErrorMessage';

type MarketplaceFilter = 'all' | 'installed' | 'updates';

function matchesFilter(item: PluginMarketplaceItem, filter: MarketplaceFilter): boolean {
  if (filter === 'installed') {
    return item.installed;
  }
  if (filter === 'updates') {
    return item.updateAvailable;
  }
  return true;
}

function pluginIcon(pluginId: string): IconType {
  if (pluginId.endsWith('/browser')) {
    return FiGlobe;
  }
  if (pluginId.endsWith('/brave-search')) {
    return FiSearch;
  }
  if (pluginId.endsWith('/weather')) {
    return FiCloud;
  }
  if (pluginId.endsWith('/mail')) {
    return FiMail;
  }
  if (pluginId.endsWith('/telegram')) {
    return FiMessageSquare;
  }
  if (pluginId.endsWith('/whisper')) {
    return FiMic;
  }
  if (pluginId.endsWith('/elevenlabs')) {
    return FiVolume2;
  }
  if (pluginId.endsWith('/lightrag')) {
    return FiCpu;
  }
  return FiPackage;
}

function matchesSearch(item: PluginMarketplaceItem, query: string): boolean {
  if (query.length === 0) {
    return true;
  }
  return item.name.toLowerCase().includes(query)
    || item.id.toLowerCase().includes(query)
    || item.provider.toLowerCase().includes(query);
}

function installLabel(item: PluginMarketplaceItem, pendingPluginId: string | null): string {
  if (pendingPluginId === item.id) {
    return item.updateAvailable ? 'Updating...' : 'Installing...';
  }
  if (item.updateAvailable) {
    return `Update to ${item.version}`;
  }
  if (item.installed) {
    return 'Installed';
  }
  return 'Install';
}

function installVariant(item: PluginMarketplaceItem): string {
  if (item.updateAvailable) {
    return 'primary';
  }
  if (item.installed) {
    return 'outline-primary';
  }
  return 'primary';
}

function compatibilityLabel(item: PluginMarketplaceItem): string {
  if (!item.compatible) {
    return 'Incompatible';
  }
  if (!item.artifactAvailable) {
    return 'Artifact missing';
  }
  if (item.updateAvailable) {
    return 'Update available';
  }
  if (item.loaded) {
    return 'Loaded';
  }
  if (item.installed) {
    return 'Installed';
  }
  return 'Available';
}

function compatibilityVariant(item: PluginMarketplaceItem): string {
  if (!item.compatible || !item.artifactAvailable) {
    return 'danger';
  }
  if (item.updateAvailable) {
    return 'warning';
  }
  if (item.loaded) {
    return 'success';
  }
  return 'secondary';
}

export default function PluginsMarketplaceTab(): ReactElement {
  const navigate = useNavigate();
  const marketplaceQuery = usePluginMarketplace();
  const installMutation = useInstallPluginFromMarketplace();
  const [searchQuery, setSearchQuery] = useState('');
  const [filter, setFilter] = useState<MarketplaceFilter>('all');
  const deferredSearch = useDeferredValue(searchQuery.trim().toLowerCase());

  if (marketplaceQuery.isLoading) {
    return (
      <Card className="settings-card plugin-market-hero">
        <Card.Body className="d-flex align-items-center gap-2">
          <Spinner size="sm" animation="border" />
          <span className="small text-body-secondary">Loading plugin marketplace...</span>
        </Card.Body>
      </Card>
    );
  }

  if (marketplaceQuery.isError || marketplaceQuery.data == null) {
    return (
      <Card className="settings-card">
        <Card.Body>
          <SettingsCardTitle title="Plugin Marketplace" />
          <Alert variant="warning" className="mb-0">
            Unable to load marketplace metadata from backend.
          </Alert>
        </Card.Body>
      </Card>
    );
  }

  const catalog = marketplaceQuery.data;
  const items = catalog.items.filter((item) => matchesFilter(item, filter) && matchesSearch(item, deferredSearch));
  const pendingPluginId = installMutation.isPending ? installMutation.variables?.pluginId ?? null : null;
  const installedCount = catalog.items.filter((item) => item.installed).length;
  const updatesCount = catalog.items.filter((item) => item.updateAvailable).length;

  const handleInstall = async (item: PluginMarketplaceItem): Promise<void> => {
    try {
      const result = await installMutation.mutateAsync({ pluginId: item.id, version: item.version });
      toast.success(result.message);
    } catch (error: unknown) {
      toast.error(`Install failed: ${extractErrorMessage(error)}`);
    }
  };

  return (
    <div className="plugin-marketplace">
      <Card className="settings-card plugin-market-hero mb-3">
        <Card.Body>
          <div className="plugin-market-hero-head">
            <div>
              <div className="plugin-market-eyebrow">Extensions</div>
              <SettingsCardTitle title="Plugin Marketplace" tip="Install and update plugin-backed integrations without leaving Settings." />
              <p className="text-body-secondary mb-0 plugin-market-hero-copy">
                Browse available integrations, install them into this runtime, and jump straight into setup for
                plugins that need credentials, endpoints, or provider-specific options.
              </p>
            </div>
            <div className="plugin-market-hero-stats" aria-label="Marketplace statistics">
              <div className="plugin-market-stat">
                <span className="plugin-market-stat-value">{catalog.items.length}</span>
                <span className="plugin-market-stat-label">Available</span>
              </div>
              <div className="plugin-market-stat">
                <span className="plugin-market-stat-value">{installedCount}</span>
                <span className="plugin-market-stat-label">Installed</span>
              </div>
              <div className="plugin-market-stat">
                <span className="plugin-market-stat-value">{updatesCount}</span>
                <span className="plugin-market-stat-label">Updates</span>
              </div>
            </div>
          </div>

          <Row className="g-3 mt-1 align-items-end">
            <Col lg={7}>
              <Form.Group controlId="plugin-marketplace-search">
                <Form.Label className="small fw-medium">Search by name</Form.Label>
                <InputGroup className="plugin-market-search">
                  <InputGroup.Text><FiSearch size={16} /></InputGroup.Text>
                  <Form.Control
                    type="search"
                    placeholder="Search plugins by name or provider"
                    value={searchQuery}
                    onChange={(event) => setSearchQuery(event.target.value)}
                  />
                </InputGroup>
              </Form.Group>
            </Col>
            <Col lg={5}>
              <div className="plugin-market-filter-row" role="tablist" aria-label="Plugin marketplace filters">
                {([
                  { key: 'all', label: 'All', count: catalog.items.length },
                  { key: 'installed', label: 'Installed', count: installedCount },
                  { key: 'updates', label: 'Updates', count: updatesCount },
                ] as const).map((entry) => (
                  <button
                    key={entry.key}
                    type="button"
                    className={`plugin-market-filter${filter === entry.key ? ' active' : ''}`}
                    onClick={() => setFilter(entry.key)}
                    aria-pressed={filter === entry.key}
                  >
                    <span>{entry.label}</span>
                    <Badge bg={filter === entry.key ? 'light' : 'secondary'} text={filter === entry.key ? 'dark' : undefined}>
                      {entry.count}
                    </Badge>
                  </button>
                ))}
              </div>
            </Col>
          </Row>

          {catalog.sourceDirectory != null && catalog.sourceDirectory.length > 0 && (
            <div className="plugin-market-source small text-body-secondary mt-3">
              Source: <span className="text-body">{catalog.sourceDirectory}</span>
            </div>
          )}
        </Card.Body>
      </Card>

      {!catalog.available && (
        <Alert variant="warning" className="mb-3">
          {catalog.message ?? 'Marketplace is unavailable in this environment.'}
        </Alert>
      )}

      {catalog.available && items.length === 0 && (
        <Card className="settings-card plugin-market-empty">
          <Card.Body>
            <div className="plugin-market-empty-icon"><FiPackage size={22} /></div>
            <h3 className="h6 mb-1">No plugins match this search</h3>
            <p className="text-body-secondary small mb-0">
              Try a different name or switch the marketplace filter.
            </p>
          </Card.Body>
        </Card>
      )}

      {catalog.available && items.length > 0 && (
        <Row className="g-3">
          {items.map((item) => {
            const Icon = pluginIcon(item.id);
            const canInstall = item.compatible && item.artifactAvailable && !installMutation.isPending;
            const showOpenSettings = item.installed && item.settingsRouteKey != null;
            return (
              <Col key={item.id} md={6} xl={4}>
                <Card className={`settings-card plugin-market-card h-100${item.updateAvailable ? ' is-update' : ''}${item.installed ? ' is-installed' : ''}`}>
                  <Card.Body className="d-flex flex-column">
                    <div className="plugin-market-card-head">
                      <div className="plugin-market-icon-shell">
                        <Icon size={20} />
                      </div>
                      <div className="min-w-0 flex-grow-1">
                        <div className="plugin-market-title-row">
                          <h3 className="h6 mb-0">{item.name}</h3>
                          {item.official && <Badge bg="dark-subtle" text="dark">Official</Badge>}
                        </div>
                        <div className="plugin-market-plugin-id">{item.id}</div>
                      </div>
                    </div>

                    <p className="text-body-secondary small mt-3 mb-3 plugin-market-description">
                      {item.description ?? 'No description provided.'}
                    </p>

                    <div className="plugin-market-badges mb-3">
                      <Badge bg={compatibilityVariant(item)}>{compatibilityLabel(item)}</Badge>
                      <Badge bg="secondary">v{item.version}</Badge>
                      {item.installedVersion != null && (
                        <Badge bg="light" text="dark">Installed {item.installedVersion}</Badge>
                      )}
                    </div>

                    <div className="plugin-market-meta small text-body-secondary mb-3">
                      {item.engineVersion != null && (
                        <div>Engine: <span className="text-body">{item.engineVersion}</span></div>
                      )}
                      {item.license != null && (
                        <div>License: <span className="text-body">{item.license}</span></div>
                      )}
                      {item.maintainers.length > 0 && (
                        <div>Maintainers: <span className="text-body">{item.maintainers.join(', ')}</span></div>
                      )}
                    </div>

                    {!item.compatible && (
                      <Alert variant="danger" className="small py-2 mb-3">
                        This plugin version does not match the current engine compatibility range.
                      </Alert>
                    )}

                    {item.compatible && !item.artifactAvailable && (
                      <Alert variant="warning" className="small py-2 mb-3">
                        Artifact is missing from the marketplace source, so installation is currently unavailable.
                      </Alert>
                    )}

                    <div className="mt-auto d-flex flex-wrap gap-2">
                      {showOpenSettings && (
                        <Button
                          type="button"
                          variant="secondary"
                          size="sm"
                          onClick={() => navigate(`/settings/${item.settingsRouteKey}`)}
                        >
                          <FiSettings size={14} className="me-1" />
                          Open settings
                        </Button>
                      )}

                      <Button
                        type="button"
                        variant={installVariant(item)}
                        size="sm"
                        disabled={item.installed && !item.updateAvailable || !canInstall}
                        onClick={() => { void handleInstall(item); }}
                      >
                        {pendingPluginId === item.id
                          ? <Spinner size="sm" animation="border" className="me-1" />
                          : item.installed && !item.updateAvailable
                            ? <FiCheckCircle size={14} className="me-1" />
                            : <FiDownloadCloud size={14} className="me-1" />}
                        {installLabel(item, pendingPluginId)}
                      </Button>
                    </div>
                  </Card.Body>
                </Card>
              </Col>
            );
          })}
        </Row>
      )}
    </div>
  );
}
