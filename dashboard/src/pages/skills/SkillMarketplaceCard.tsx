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

export function SkillMarketplaceCard({
  item,
  isPending,
  pendingSkillId,
  onInstall,
}: SkillMarketplaceCardProps): ReactElement {
  const artifactKind = item.artifactType === 'pack' ? 'Pack' : 'Skill';

  return (
    <Card
      className={cn(
        'h-full overflow-hidden transition-all duration-200',
        item.updateAvailable && 'border-amber-500/30 bg-amber-500/[0.06]',
        item.installed && !item.updateAvailable && 'border-green-500/25',
      )}
    >
      <CardContent className="flex h-full flex-col gap-4 p-5">
        <div className="flex items-start justify-between gap-3">
          <div className="min-w-0 space-y-1">
            <h3 className="text-lg font-semibold tracking-tight text-foreground">{item.name}</h3>
            <p className="break-all text-xs uppercase tracking-[0.18em] text-muted-foreground">
              {item.id}
            </p>
          </div>
          <Badge variant={statusVariant(item)}>
            {item.updateAvailable ? 'Update' : item.installed ? 'Installed' : 'Available'}
          </Badge>
        </div>

        <p className="text-sm leading-6 text-muted-foreground">
          {item.description ?? 'No description provided.'}
        </p>

        <div className="grid gap-2 rounded-2xl border border-border/70 bg-muted/25 p-4 text-sm text-muted-foreground sm:grid-cols-2">
          <div>
            Type
            <div className="mt-1 font-medium text-foreground">{artifactKind}</div>
          </div>
          {item.maintainer != null && item.maintainer.length > 0 && (
            <div>
              Maintainer
              <div className="mt-1 font-medium text-foreground">{item.maintainerDisplayName ?? item.maintainer}</div>
            </div>
          )}
          {item.version != null && item.version.length > 0 && (
            <div>
              Version
              <div className="mt-1 font-medium text-foreground">{item.version}</div>
            </div>
          )}
          <div>
            Included skills
            <div className="mt-1 font-medium text-foreground">{item.skillCount}</div>
          </div>
          {item.modelTier != null && item.modelTier.length > 0 && (
            <div>
              Recommended tier
              <div className="mt-1 font-medium text-foreground">{item.modelTier}</div>
            </div>
          )}
          {item.sourcePath != null && item.sourcePath.length > 0 && (
            <div className="sm:col-span-2">
              Source
              <div className="mt-1 break-all font-medium text-foreground">{item.sourcePath}</div>
            </div>
          )}
        </div>

        {item.skillRefs.length > 0 && (
          <div className="space-y-2">
            <div className="flex items-center gap-2 text-xs font-semibold uppercase tracking-[0.2em] text-muted-foreground">
              <FiLayers size={14} />
              Runtime refs
            </div>
            <div className="flex flex-wrap gap-2">
              {item.skillRefs.slice(0, 4).map((skillRef) => (
                <Badge key={skillRef} variant="light" className="normal-case tracking-normal">
                  {skillRef}
                </Badge>
              ))}
              {item.skillRefs.length > 4 && (
                <Badge variant="secondary" className="normal-case tracking-normal">
                  +{item.skillRefs.length - 4} more
                </Badge>
              )}
            </div>
          </div>
        )}

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
      </CardContent>
    </Card>
  );
}
