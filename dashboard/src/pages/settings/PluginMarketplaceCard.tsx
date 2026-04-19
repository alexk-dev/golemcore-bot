import type { ReactElement } from 'react';
import { Alert, Badge, Button, Card, Spinner } from '../../components/ui/tailwind-components';
import type { IconType } from 'react-icons';
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
  FiTrash2,
  FiVolume2,
} from 'react-icons/fi';

import type { PluginMarketplaceItem } from '../../api/plugins';
import {
  compatibilityLabel,
  compatibilityVariant,
  installLabel,
  installVariant,
  isPendingForItem,
  type MarketplacePendingAction,
  uninstallLabel,
} from './pluginMarketplaceUi';

interface PluginMarketplaceCardProps {
  item: PluginMarketplaceItem;
  pendingAction: MarketplacePendingAction;
  pendingPluginId: string | null;
  onInstall: (item: PluginMarketplaceItem) => void;
  onUninstall: (item: PluginMarketplaceItem) => void;
  onOpenSettings: (routeKey: string) => void;
}

function pluginIcon(pluginId: string): IconType {
  if (pluginId.endsWith('/browser')) {
    return FiGlobe;
  }
  if (pluginId.endsWith('/brave-search')) {
    return FiSearch;
  }
  if (pluginId.endsWith('/tavily-search')) {
    return FiSearch;
  }
  if (pluginId.endsWith('/firecrawl')) {
    return FiGlobe;
  }
  if (pluginId.endsWith('/perplexity-sonar')) {
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

interface PluginMarketplaceMetaProps {
  item: PluginMarketplaceItem;
}

interface PluginMarketplaceActionsProps {
  item: PluginMarketplaceItem;
  pendingAction: MarketplacePendingAction;
  pendingPluginId: string | null;
  onInstall: (item: PluginMarketplaceItem) => void;
  onUninstall: (item: PluginMarketplaceItem) => void;
  onOpenSettings: (routeKey: string) => void;
}

function PluginMarketplaceMeta({ item }: PluginMarketplaceMetaProps): ReactElement {
  return (
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
  );
}

function PluginMarketplaceAvailabilityAlert({ item }: PluginMarketplaceMetaProps): ReactElement | null {
  if (!item.compatible) {
    return (
      <Alert variant="danger" className="small py-2 mb-3">
        This plugin version requires a newer engine.
      </Alert>
    );
  }

  if (!item.artifactAvailable) {
    return (
      <Alert variant="warning" className="small py-2 mb-3">
        Artifact is missing from the marketplace source, so installation is currently unavailable.
      </Alert>
    );
  }

  return null;
}

function PluginMarketplaceInstallIcon({
  item,
  pendingAction,
  pendingPluginId,
}: Pick<PluginMarketplaceActionsProps, 'item' | 'pendingAction' | 'pendingPluginId'>): ReactElement {
  if (isPendingForItem(item, pendingAction, pendingPluginId, 'install')) {
    return <Spinner size="sm" animation="border" className="me-1" />;
  }

  if (item.installed && !item.updateAvailable) {
    return <FiCheckCircle size={14} className="me-1" />;
  }

  return <FiDownloadCloud size={14} className="me-1" />;
}

function PluginMarketplaceActions({
  item,
  pendingAction,
  pendingPluginId,
  onInstall,
  onUninstall,
  onOpenSettings,
}: PluginMarketplaceActionsProps): ReactElement {
  const settingsRouteKey = item.settingsRouteKey ?? null;
  const isMutating = pendingAction != null;
  const canInstall = item.compatible && item.artifactAvailable && !isMutating;
  const showOpenSettings = item.installed && settingsRouteKey != null;
  const showUninstall = item.installed;

  return (
    <div className="mt-auto d-flex flex-wrap gap-2">
      {showOpenSettings && settingsRouteKey != null && (
        <Button
          type="button"
          variant="secondary"
          size="sm"
          disabled={isMutating}
          onClick={() => onOpenSettings(settingsRouteKey)}
        >
          <FiSettings size={14} className="me-1" />
          Open settings
        </Button>
      )}

      {showUninstall && (
        <Button
          type="button"
          variant="danger"
          size="sm"
          disabled={isMutating}
          onClick={() => onUninstall(item)}
        >
          {isPendingForItem(item, pendingAction, pendingPluginId, 'uninstall') ? (
            <Spinner size="sm" animation="border" className="me-1" />
          ) : (
            <FiTrash2 size={14} className="me-1" />
          )}
          {uninstallLabel(item, pendingAction, pendingPluginId)}
        </Button>
      )}

      <Button
        type="button"
        variant={installVariant(item)}
        size="sm"
        disabled={(item.installed && !item.updateAvailable) || !canInstall}
        onClick={() => onInstall(item)}
      >
        <PluginMarketplaceInstallIcon
          item={item}
          pendingAction={pendingAction}
          pendingPluginId={pendingPluginId}
        />
        {installLabel(item, pendingAction, pendingPluginId)}
      </Button>
    </div>
  );
}

export function PluginMarketplaceCard({
  item,
  pendingAction,
  pendingPluginId,
  onInstall,
  onUninstall,
  onOpenSettings,
}: PluginMarketplaceCardProps): ReactElement {
  const Icon = pluginIcon(item.id);

  return (
    <Card className={`settings-card plugin-market-card h-100${item.updateAvailable ? ' is-update' : ''}${item.installed ? ' is-installed' : ''}`}>
      <Card.Body className="d-flex flex-column">
        <div className="plugin-market-card-head">
          <div className="plugin-market-icon-shell">
            <Icon size={20} />
          </div>
          <div className="min-w-0 flex-grow-1">
            <div className="plugin-market-title-row">
              <h3 className="h6 mb-0">{item.name}</h3>
              {item.official && <span className="badge plugin-market-official-badge">Official</span>}
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

        <PluginMarketplaceMeta item={item} />
        <PluginMarketplaceAvailabilityAlert item={item} />
        <PluginMarketplaceActions
          item={item}
          pendingAction={pendingAction}
          pendingPluginId={pendingPluginId}
          onInstall={onInstall}
          onUninstall={onUninstall}
          onOpenSettings={onOpenSettings}
        />
      </Card.Body>
    </Card>
  );
}
