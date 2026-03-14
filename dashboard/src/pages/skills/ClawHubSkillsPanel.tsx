import { type ReactElement, useDeferredValue, useMemo, useState } from 'react';
import toast from 'react-hot-toast';
import { FiCheckCircle, FiDownloadCloud, FiSearch } from 'react-icons/fi';
import type { ClawHubSkillItem } from '../../api/skills';
import { Alert } from '../../components/ui/alert';
import { Badge } from '../../components/ui/badge';
import { Button } from '../../components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '../../components/ui/card';
import { Input } from '../../components/ui/field';
import { useClawHubSkills, useInstallClawHubSkill } from '../../hooks/useSkills';
import { extractErrorMessage } from '../../utils/extractErrorMessage';

function formatUpdatedAt(value: number | null | undefined): string | null {
  if (value == null || !Number.isFinite(value)) {
    return null;
  }
  return new Date(value).toLocaleDateString();
}

function installLabel(item: ClawHubSkillItem, pendingSlug: string | null): string {
  if (pendingSlug === item.slug) {
    return 'Installing...';
  }
  if (item.installed) {
    return 'Installed';
  }
  return 'Install';
}

function ClawHubCard({
  item,
  pendingSlug,
  isPending,
  onInstall,
}: {
  item: ClawHubSkillItem;
  pendingSlug: string | null;
  isPending: boolean;
  onInstall: (item: ClawHubSkillItem) => void;
}): ReactElement {
  return (
    <Card className={item.installed ? 'border-green-500/25' : ''}>
      <CardContent className="flex h-full flex-col gap-4 p-5">
        <div className="flex items-start justify-between gap-3">
          <div className="min-w-0 space-y-1">
            <h3 className="text-lg font-semibold tracking-tight text-foreground">{item.displayName}</h3>
            <p className="break-all text-xs uppercase tracking-[0.18em] text-muted-foreground">{item.slug}</p>
          </div>
          <Badge variant={item.installed ? 'success' : 'secondary'}>
            {item.installed ? 'Installed' : 'Public'}
          </Badge>
        </div>

        <p className="text-sm leading-6 text-muted-foreground">
          {item.summary ?? 'No summary provided.'}
        </p>

        <div className="grid gap-2 rounded-2xl border border-border/70 bg-muted/25 p-4 text-sm text-muted-foreground">
          <div>
            Runtime
            <div className="mt-1 font-medium text-foreground">{item.runtimeName}</div>
          </div>
          {item.version != null && item.version.length > 0 && (
            <div>
              Version
              <div className="mt-1 font-medium text-foreground">{item.version}</div>
            </div>
          )}
          {item.installedVersion != null && item.installedVersion.length > 0 && (
            <div>
              Installed version
              <div className="mt-1 font-medium text-foreground">{item.installedVersion}</div>
            </div>
          )}
          {formatUpdatedAt(item.updatedAt) != null && (
            <div>
              Updated
              <div className="mt-1 font-medium text-foreground">{formatUpdatedAt(item.updatedAt)}</div>
            </div>
          )}
        </div>

        <div className="mt-auto pt-2">
          <Button
            type="button"
            size="sm"
            variant={item.installed ? 'secondary' : 'default'}
            disabled={isPending || item.installed}
            onClick={() => onInstall(item)}
          >
            {pendingSlug === item.slug ? (
              <span className="h-3.5 w-3.5 animate-spin rounded-full border-2 border-current border-r-transparent" />
            ) : item.installed ? (
              <FiCheckCircle size={14} />
            ) : (
              <FiDownloadCloud size={14} />
            )}
            {installLabel(item, pendingSlug)}
          </Button>
        </div>
      </CardContent>
    </Card>
  );
}

export function ClawHubSkillsPanel(): ReactElement {
  const [searchQuery, setSearchQuery] = useState('');
  const deferredSearch = useDeferredValue(searchQuery.trim());
  const catalogQuery = useClawHubSkills(deferredSearch);
  const installMutation = useInstallClawHubSkill();

  const pendingSlug = installMutation.isPending ? installMutation.variables?.slug ?? null : null;
  const items = catalogQuery.data?.items ?? [];
  const installedCount = useMemo(() => items.filter((item) => item.installed).length, [items]);

  const handleInstall = async (item: ClawHubSkillItem): Promise<void> => {
    try {
      const result = await installMutation.mutateAsync({ slug: item.slug, version: item.version ?? null });
      toast.success(result.message);
    } catch (error: unknown) {
      toast.error(`Install failed: ${extractErrorMessage(error)}`);
    }
  };

  return (
    <div className="space-y-4">
      <Card className="overflow-hidden border-primary/15 bg-[linear-gradient(135deg,rgba(16,185,129,0.08),rgba(15,23,42,0.02))]">
        <CardHeader className="items-start">
          <div className="space-y-2">
            <div className="text-[0.72rem] font-semibold uppercase tracking-[0.28em] text-primary/80">
              External Registry
            </div>
            <CardTitle className="text-lg">ClawHub</CardTitle>
            <CardDescription>
              Install public ClawHub skills into the local workspace. Imported skills are namespaced as `clawhub/&lt;slug&gt;`.
            </CardDescription>
          </div>
          <Badge variant="secondary" className="normal-case tracking-normal">
            {catalogQuery.data?.siteUrl ?? 'https://clawhub.ai'}
          </Badge>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="grid gap-3 xl:grid-cols-[minmax(0,1fr)_16rem] xl:items-end">
            <div className="space-y-2">
              <label htmlFor="clawhub-search" className="text-sm font-medium text-foreground">
                Search
              </label>
              <div className="relative">
                <FiSearch className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-muted-foreground" size={16} />
                <Input
                  id="clawhub-search"
                  type="search"
                  placeholder="Search public ClawHub skills"
                  value={searchQuery}
                  onChange={(event) => setSearchQuery(event.target.value)}
                  className="pl-10"
                />
              </div>
              <p className="text-xs leading-5 text-muted-foreground">
                {deferredSearch.length > 0 ? 'Showing search results.' : 'Showing latest updated public skills.'}
              </p>
            </div>

            <div className="rounded-2xl border border-border/70 bg-background/70 px-4 py-3">
              <div className="text-2xl font-semibold tracking-tight text-foreground">{installedCount}</div>
              <div className="mt-1 text-xs font-semibold uppercase tracking-[0.2em] text-muted-foreground">
                Installed in result set
              </div>
            </div>
          </div>
        </CardContent>
      </Card>

      {catalogQuery.isLoading && (
        <Card>
          <CardContent className="flex items-center gap-3 p-5 text-sm text-muted-foreground">
            <span className="h-4 w-4 animate-spin rounded-full border-2 border-current border-r-transparent" />
            <span>Loading ClawHub skills...</span>
          </CardContent>
        </Card>
      )}

      {catalogQuery.isError && (
        <Alert variant="warning">
          Unable to load the ClawHub catalog from the backend.
        </Alert>
      )}

      {catalogQuery.data != null && !catalogQuery.data.available && (
        <Alert variant="warning">
          {catalogQuery.data.message ?? 'ClawHub integration is unavailable.'}
        </Alert>
      )}

      {catalogQuery.data != null && catalogQuery.data.available && items.length === 0 && (
        <Card>
          <CardContent className="py-12 text-center">
            <h3 className="text-base font-semibold text-foreground">No skills found</h3>
            <p className="mt-1 text-sm text-muted-foreground">
              Try another query or clear the search field to see the latest ClawHub skills.
            </p>
          </CardContent>
        </Card>
      )}

      {items.length > 0 && (
        <div className="grid gap-4 md:grid-cols-2 2xl:grid-cols-3">
          {items.map((item) => (
            <ClawHubCard
              key={item.slug}
              item={item}
              pendingSlug={pendingSlug}
              isPending={installMutation.isPending}
              onInstall={(skill) => { void handleInstall(skill); }}
            />
          ))}
        </div>
      )}
    </div>
  );
}
