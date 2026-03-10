import type { ReactElement } from 'react';
import { Badge, Button, Card, Spinner } from 'react-bootstrap';
import { FiDownloadCloud, FiCheckCircle } from 'react-icons/fi';
import type { SkillMarketplaceItem } from '../../api/skills';

interface SkillMarketplaceCardProps {
  item: SkillMarketplaceItem;
  isPending: boolean;
  pendingSkillId: string | null;
  onInstall: (item: SkillMarketplaceItem) => void;
}

function installLabel(item: SkillMarketplaceItem, pendingSkillId: string | null): string {
  if (pendingSkillId === item.id) {
    return item.updateAvailable ? 'Updating...' : 'Installing...';
  }
  if (item.updateAvailable) {
    return 'Update';
  }
  if (item.installed) {
    return 'Installed';
  }
  return 'Install';
}

function installVariant(item: SkillMarketplaceItem): 'primary' | 'secondary' {
  if (item.installed && !item.updateAvailable) {
    return 'secondary';
  }
  return 'primary';
}

function installDisabled(item: SkillMarketplaceItem, isPending: boolean): boolean {
  return isPending || (item.installed && !item.updateAvailable);
}

export function SkillMarketplaceCard({
  item,
  isPending,
  pendingSkillId,
  onInstall,
}: SkillMarketplaceCardProps): ReactElement {
  return (
    <Card className={`settings-card plugin-market-card h-100${item.updateAvailable ? ' is-update' : ''}${item.installed ? ' is-installed' : ''}`}>
      <Card.Body className="d-flex flex-column">
        <div className="d-flex justify-content-between align-items-start mb-2 gap-2">
          <div>
            <h3 className="h6 mb-1">{item.name}</h3>
            <div className="plugin-market-plugin-id">{item.id}</div>
          </div>
          <Badge bg={item.updateAvailable ? 'warning' : item.installed ? 'success' : 'secondary'}>
            {item.updateAvailable ? 'Update' : item.installed ? 'Installed' : 'Available'}
          </Badge>
        </div>

        <p className="text-body-secondary small mb-3">
          {item.description ?? 'No description provided.'}
        </p>

        <div className="plugin-market-meta small text-body-secondary mb-3">
          {item.modelTier != null && item.modelTier.length > 0 && (
            <div>Recommended tier: <span className="text-body">{item.modelTier}</span></div>
          )}
          {item.sourcePath != null && item.sourcePath.length > 0 && (
            <div>Source: <span className="text-body">{item.sourcePath}</span></div>
          )}
        </div>

        <div className="mt-auto d-flex gap-2">
          <Button
            type="button"
            size="sm"
            variant={installVariant(item)}
            disabled={installDisabled(item, isPending)}
            onClick={() => onInstall(item)}
          >
            {pendingSkillId === item.id
              ? <Spinner size="sm" animation="border" className="me-1" />
              : item.installed && !item.updateAvailable
                ? <FiCheckCircle size={14} className="me-1" />
                : <FiDownloadCloud size={14} className="me-1" />}
            {installLabel(item, pendingSkillId)}
          </Button>
        </div>
      </Card.Body>
    </Card>
  );
}
