import type { ReactElement } from 'react';
import { FiCheckCircle, FiDownloadCloud, FiLayers } from 'react-icons/fi';
import type { SkillMarketplaceItem } from '../../api/skills';
import { Badge } from '../../components/ui/badge';
import { Button } from '../../components/ui/button';
import { Card, CardContent } from '../../components/ui/card';
import { cn } from '../../lib/utils';

interface SkillMarketplaceCardProps {
  item: SkillMarketplaceItem;
  isPending: boolean;
  pendingSkillId: string | null;
  onInstall: (item: SkillMarketplaceItem) => void;
}

interface MarketplaceDetailItem {
  label: string;
  value: string | number;
  fullWidth?: boolean;
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

function installVariant(item: SkillMarketplaceItem): 'default' | 'secondary' | 'warning' {
  if (item.installed && !item.updateAvailable) {
    return 'secondary';
  }
  if (item.updateAvailable) {
    return 'warning';
  }
  return 'default';
}

function installDisabled(item: SkillMarketplaceItem, isPending: boolean): boolean {
  return isPending || (item.installed && !item.updateAvailable);
}

function statusVariant(item: SkillMarketplaceItem): 'secondary' | 'success' | 'warning' {
  if (item.updateAvailable) {
    return 'warning';
  }
  if (item.installed) {
    return 'success';
  }
  return 'secondary';
}

function buildDetailItems(item: SkillMarketplaceItem, artifactKind: string): MarketplaceDetailItem[] {
  const details: MarketplaceDetailItem[] = [
    { label: 'Type', value: artifactKind },
    { label: 'Included skills', value: item.skillCount },
  ];

  if (item.maintainer != null && item.maintainer.length > 0) {
    details.push({
      label: 'Maintainer',
      value: item.maintainerDisplayName ?? item.maintainer,
    });
  }
  if (item.version != null && item.version.length > 0) {
    details.push({ label: 'Version', value: item.version });
  }
  if (item.modelTier != null && item.modelTier.length > 0) {
    details.push({ label: 'Recommended tier', value: item.modelTier });
  }
  if (item.sourcePath != null && item.sourcePath.length > 0) {
    details.push({ label: 'Source', value: item.sourcePath, fullWidth: true });
  }

  return details;
}

function cardClassName(item: SkillMarketplaceItem): string {
  return cn(
    'h-full overflow-hidden transition-all duration-200',
    item.updateAvailable && 'border-amber-500/30 bg-amber-500/[0.06]',
    item.installed && !item.updateAvailable && 'border-green-500/25',
  );
}

function availabilityLabel(item: SkillMarketplaceItem): string {
  if (item.updateAvailable) {
    return 'Update';
  }
  if (item.installed) {
    return 'Installed';
  }
  return 'Available';
}

function StatusBadge({ item }: { item: SkillMarketplaceItem }): ReactElement {
  return (
    <Badge variant={statusVariant(item)}>
      {availabilityLabel(item)}
    </Badge>
  );
}

function DetailGrid({ items }: { items: MarketplaceDetailItem[] }): ReactElement {
  return (
    <div className="grid gap-2 rounded-2xl border border-border/70 bg-muted/25 p-4 text-sm text-muted-foreground sm:grid-cols-2">
      {items.map((entry) => (
        <div key={`${entry.label}:${String(entry.value)}`} className={entry.fullWidth ? 'sm:col-span-2' : undefined}>
          {entry.label}
          <div className={cn('mt-1 font-medium text-foreground', entry.fullWidth && 'break-all')}>
            {entry.value}
          </div>
        </div>
      ))}
    </div>
  );
}

function RuntimeRefBadges({ skillRefs }: { skillRefs: string[] }): ReactElement | null {
  if (skillRefs.length === 0) {
    return null;
  }

  const visibleRefs = skillRefs.slice(0, 4);
  const hiddenRefCount = skillRefs.length - visibleRefs.length;

  return (
    <div className="space-y-2">
      <div className="flex items-center gap-2 text-xs font-semibold uppercase tracking-[0.2em] text-muted-foreground">
        <FiLayers size={14} />
        Runtime refs
      </div>
      <div className="flex flex-wrap gap-2">
        {visibleRefs.map((skillRef) => (
          <Badge key={skillRef} variant="light" className="normal-case tracking-normal">
            {skillRef}
          </Badge>
        ))}
        {hiddenRefCount > 0 && (
          <Badge variant="secondary" className="normal-case tracking-normal">
            +{hiddenRefCount} more
          </Badge>
        )}
      </div>
    </div>
  );
}

function InstallAction({
  item,
  isPending,
  pendingSkillId,
  onInstall,
}: SkillMarketplaceCardProps): ReactElement {
  return (
    <div className="mt-auto pt-2">
      <Button
        type="button"
        size="sm"
        variant={installVariant(item)}
        disabled={installDisabled(item, isPending)}
        onClick={() => onInstall(item)}
      >
        {pendingSkillId === item.id ? (
          <span className="h-3.5 w-3.5 animate-spin rounded-full border-2 border-current border-r-transparent" />
        ) : item.installed && !item.updateAvailable ? (
          <FiCheckCircle size={14} />
        ) : (
          <FiDownloadCloud size={14} />
        )}
        {installLabel(item, pendingSkillId)}
      </Button>
    </div>
  );
}

export function SkillMarketplaceCard({
  item,
  isPending,
  pendingSkillId,
  onInstall,
}: SkillMarketplaceCardProps): ReactElement {
  const artifactKind = item.artifactType === 'pack' ? 'Pack' : 'Skill';
  const detailItems = buildDetailItems(item, artifactKind);

  return (
    <Card className={cardClassName(item)}>
      <CardContent className="flex h-full flex-col gap-4 p-5">
        <div className="flex items-start justify-between gap-3">
          <div className="min-w-0 space-y-1">
            <h3 className="text-lg font-semibold tracking-tight text-foreground">{item.name}</h3>
            <p className="break-all text-xs uppercase tracking-[0.18em] text-muted-foreground">
              {item.id}
            </p>
          </div>
          <StatusBadge item={item} />
        </div>

        <p className="text-sm leading-6 text-muted-foreground">
          {item.description ?? 'No description provided.'}
        </p>

        <DetailGrid items={detailItems} />
        <RuntimeRefBadges skillRefs={item.skillRefs} />
        <InstallAction
          item={item}
          isPending={isPending}
          pendingSkillId={pendingSkillId}
          onInstall={onInstall}
        />
      </CardContent>
    </Card>
  );
}
