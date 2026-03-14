import { type ReactElement, useDeferredValue, useEffect, useMemo, useRef, useState } from 'react';
import toast from 'react-hot-toast';
import { FiChevronDown, FiFolder, FiPackage, FiSave, FiSearch } from 'react-icons/fi';
import type { SkillMarketplaceCatalogResponse, SkillMarketplaceItem } from '../../api/skills';
import type { SkillsConfig } from '../../api/settings';
import { Alert } from '../../components/ui/alert';
import { Badge } from '../../components/ui/badge';
import { Button } from '../../components/ui/button';
import { Form, InputGroup } from '../../components/ui/bootstrap-form';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '../../components/ui/card';
import { Input } from '../../components/ui/field';
import { useInstallSkillFromMarketplace, useSkillMarketplace } from '../../hooks/useSkills';
import { useRuntimeConfig, useUpdateSkills } from '../../hooks/useSettings';
import { cn } from '../../lib/utils';
import { extractErrorMessage } from '../../utils/extractErrorMessage';
import { SkillMarketplaceCard } from './SkillMarketplaceCard';

const DEFAULT_SKILLS_REPOSITORY = 'https://github.com/alexk-dev/golemcore-skills';

type MarketplaceFilter = 'all' | 'installed' | 'updates';
type MarketplaceSourceType = 'repository' | 'directory' | 'sandbox';

interface MarketplaceSourceForm {
  marketplaceSourceType: MarketplaceSourceType;
  marketplaceRepositoryDirectory: string;
  marketplaceSandboxPath: string;
  marketplaceRepositoryUrl: string;
  marketplaceBranch: string;
}

function sourceTypeLabel(sourceType: MarketplaceSourceType | null | undefined): string {
  if (sourceType === 'directory') {
    return 'Local path';
  }
  if (sourceType === 'sandbox') {
    return 'Sandbox path';
  }
  return 'Repository';
}

function sourceTypeBadgeVariant(sourceType: MarketplaceSourceType | null | undefined): 'info' | 'secondary' {
  return sourceType === 'repository' ? 'secondary' : 'info';
}

function isPathSourceType(sourceType: MarketplaceSourceType): boolean {
  return sourceType === 'directory' || sourceType === 'sandbox';
}

function pathInputLabel(sourceType: MarketplaceSourceType): string {
  return sourceType === 'sandbox' ? 'Sandbox path' : 'Local path';
}

function pathInputPlaceholder(sourceType: MarketplaceSourceType): string {
  return sourceType === 'sandbox'
    ? 'repos/golemcore-skills'
    : '/absolute/path/to/golemcore-skills';
}

function matchesFilter(item: SkillMarketplaceItem, filter: MarketplaceFilter): boolean {
  if (filter === 'installed') {
    return item.installed;
  }
  if (filter === 'updates') {
    return item.updateAvailable;
  }
  return true;
}

function matchesSearch(item: SkillMarketplaceItem, query: string): boolean {
  if (query.length === 0) {
    return true;
  }
  return item.name.toLowerCase().includes(query)
    || item.id.toLowerCase().includes(query)
    || (item.description ?? '').toLowerCase().includes(query)
    || (item.maintainer ?? '').toLowerCase().includes(query);
}

function buildSourceForm(
  config: SkillsConfig | undefined,
  catalog: SkillMarketplaceCatalogResponse | undefined,
): MarketplaceSourceForm {
  const effectiveSourceType = config?.marketplaceSourceType ?? catalog?.sourceType ?? 'repository';

  return {
    marketplaceSourceType: effectiveSourceType === 'directory' || effectiveSourceType === 'sandbox'
      ? effectiveSourceType
      : 'repository',
    marketplaceRepositoryDirectory: config?.marketplaceRepositoryDirectory
      ?? (catalog?.sourceType === 'directory' ? catalog.sourceDirectory ?? '' : ''),
    marketplaceSandboxPath: config?.marketplaceSandboxPath
      ?? (catalog?.sourceType === 'sandbox' ? catalog.sourceDirectory ?? '' : ''),
    marketplaceRepositoryUrl: config?.marketplaceRepositoryUrl
      ?? (catalog?.sourceType === 'repository' ? catalog.sourceDirectory ?? '' : DEFAULT_SKILLS_REPOSITORY),
    marketplaceBranch: config?.marketplaceBranch ?? 'main',
  };
}

function sourceSummaryLabel(catalog: SkillMarketplaceCatalogResponse | undefined): string {
  const sourceDirectory = catalog?.sourceDirectory ?? '';
  if (sourceDirectory.length === 0) {
    return 'Marketplace source has not been resolved yet.';
  }
  if (catalog?.sourceType === 'directory') {
    return `Resolved local path: ${sourceDirectory}`;
  }
  if (catalog?.sourceType === 'sandbox') {
    return `Resolved sandbox path: ${sourceDirectory}`;
  }
  return `Resolved repository: ${sourceDirectory}`;
}

function StatCard({ label, value }: { label: string; value: number }): ReactElement {
  return (
    <div className="rounded-2xl border border-border/70 bg-background/70 px-4 py-3">
      <div className="text-2xl font-semibold tracking-tight text-foreground">{value}</div>
      <div className="mt-1 text-xs font-semibold uppercase tracking-[0.2em] text-muted-foreground">{label}</div>
    </div>
  );
}

function LoadingState(): ReactElement {
  return (
    <Card>
      <CardContent className="flex items-center gap-3 p-5 text-sm text-muted-foreground">
        <span className="h-4 w-4 animate-spin rounded-full border-2 border-current border-r-transparent" />
        <span>Loading skills marketplace...</span>
      </CardContent>
    </Card>
  );
}

export function SkillsMarketplacePanel(): ReactElement {
  const marketplaceQuery = useSkillMarketplace();
  const runtimeConfigQuery = useRuntimeConfig();
  const installMutation = useInstallSkillFromMarketplace();
  const updateSkillsMutation = useUpdateSkills();

  const [searchQuery, setSearchQuery] = useState('');
  const [filter, setFilter] = useState<MarketplaceFilter>('all');
  const [sourceForm, setSourceForm] = useState<MarketplaceSourceForm>(() => buildSourceForm(undefined, undefined));
  const [isSourceEditorOpen, setIsSourceEditorOpen] = useState(false);
  const lastSyncedSourceSignatureRef = useRef<string | null>(null);

  const deferredSearch = useDeferredValue(searchQuery.trim().toLowerCase());
  const skillsConfig = runtimeConfigQuery.data?.skills;
  const baselineSourceForm = useMemo(
    () => buildSourceForm(skillsConfig, marketplaceQuery.data),
    [marketplaceQuery.data, skillsConfig],
  );
  const baselineSourceSignature = useMemo(
    () => JSON.stringify(baselineSourceForm),
    [baselineSourceForm],
  );

  useEffect(() => {
    const currentSignature = JSON.stringify(sourceForm);

    if (lastSyncedSourceSignatureRef.current == null) {
      setSourceForm(baselineSourceForm);
      lastSyncedSourceSignatureRef.current = baselineSourceSignature;
      return;
    }

    if (
      currentSignature === lastSyncedSourceSignatureRef.current
      && currentSignature !== baselineSourceSignature
    ) {
      setSourceForm(baselineSourceForm);
      lastSyncedSourceSignatureRef.current = baselineSourceSignature;
    }
  }, [baselineSourceForm, baselineSourceSignature, sourceForm]);

  const isSourceDirty = useMemo(() => {
    return JSON.stringify(sourceForm) !== baselineSourceSignature;
  }, [baselineSourceSignature, sourceForm]);

  const handleInstall = async (item: SkillMarketplaceItem): Promise<void> => {
    try {
      const result = await installMutation.mutateAsync({ skillId: item.id });
      toast.success(result.message);
    } catch (error: unknown) {
      toast.error(`Install failed: ${extractErrorMessage(error)}`);
    }
  };

  const handleSaveSource = async (): Promise<void> => {
    if (skillsConfig == null) {
      return;
    }
    const normalizedSourceForm: MarketplaceSourceForm = {
      ...sourceForm,
      marketplaceRepositoryDirectory: sourceForm.marketplaceRepositoryDirectory.trim(),
      marketplaceSandboxPath: sourceForm.marketplaceSandboxPath.trim(),
      marketplaceRepositoryUrl: sourceForm.marketplaceRepositoryUrl.trim(),
      marketplaceBranch: sourceForm.marketplaceBranch.trim(),
    };
    try {
      await updateSkillsMutation.mutateAsync({
        ...skillsConfig,
        marketplaceSourceType: normalizedSourceForm.marketplaceSourceType,
        marketplaceRepositoryDirectory: normalizedSourceForm.marketplaceRepositoryDirectory || null,
        marketplaceSandboxPath: normalizedSourceForm.marketplaceSandboxPath || null,
        marketplaceRepositoryUrl: normalizedSourceForm.marketplaceRepositoryUrl || null,
        marketplaceBranch: normalizedSourceForm.marketplaceBranch || null,
      });
      setSourceForm(normalizedSourceForm);
      lastSyncedSourceSignatureRef.current = JSON.stringify(normalizedSourceForm);
      toast.success('Marketplace source updated');
    } catch (error: unknown) {
      toast.error(`Failed to update marketplace source: ${extractErrorMessage(error)}`);
    }
  };

  if (marketplaceQuery.isLoading) {
    return <LoadingState />;
  }

  if (marketplaceQuery.isError || marketplaceQuery.data == null) {
    return (
      <Card>
        <CardContent className="p-5">
          <Alert variant="warning">
            Unable to load skills marketplace metadata from the backend.
          </Alert>
        </CardContent>
      </Card>
    );
  }

  const catalog = marketplaceQuery.data;
  const items = catalog.items.filter((item) => matchesFilter(item, filter) && matchesSearch(item, deferredSearch));
  const pendingSkillId = installMutation.isPending ? installMutation.variables?.skillId ?? null : null;
  const installedCount = catalog.items.filter((item) => item.installed).length;
  const updatesCount = catalog.items.filter((item) => item.updateAvailable).length;
  const packCount = catalog.items.filter((item) => item.artifactType === 'pack').length;

  return (
    <div className="space-y-4">
      <Card>
        <CardHeader className="items-start">
          <div className="space-y-2">
            <div className="text-[0.72rem] font-semibold uppercase tracking-[0.28em] text-primary/80">
              Marketplace Source
            </div>
            <CardTitle className="text-lg">Artifact registry input</CardTitle>
            <CardDescription>
              Choose a sandbox path, local path, or remote repository. Repository mode defaults to the canonical `golemcore-skills` source.
            </CardDescription>
          </div>
          <Badge variant={sourceTypeBadgeVariant(catalog.sourceType)}>
            {sourceTypeLabel(catalog.sourceType)}
          </Badge>
        </CardHeader>
        <CardContent className="space-y-5">
          <div className="flex flex-col gap-4 rounded-2xl border border-border/70 bg-muted/10 p-4">
            <div className="flex flex-col gap-3 lg:flex-row lg:items-start lg:justify-between">
              <div className="min-w-0 space-y-3">
                <div className="flex flex-wrap items-center gap-2">
                  <Badge variant={sourceTypeBadgeVariant(catalog.sourceType)}>
                    {sourceTypeLabel(catalog.sourceType)}
                  </Badge>
                  {isSourceDirty && (
                    <Badge variant="warning">Unsaved</Badge>
                  )}
                </div>
                <div className="rounded-2xl border border-border/70 bg-background/80 px-4 py-3 text-sm leading-6 text-muted-foreground">
                  <div className="text-[0.68rem] font-semibold uppercase tracking-[0.18em] text-muted-foreground">
                    Current source
                  </div>
                  <div className="mt-1 break-all text-foreground">{sourceSummaryLabel(catalog)}</div>
                  {sourceForm.marketplaceSourceType === 'repository' && sourceForm.marketplaceBranch.trim().length > 0 && (
                    <div className="mt-1 text-xs text-muted-foreground">
                      Branch: <span className="font-medium text-foreground">{sourceForm.marketplaceBranch.trim()}</span>
                    </div>
                  )}
                </div>
              </div>

              <Button
                type="button"
                variant="secondary"
                aria-expanded={isSourceEditorOpen}
                onClick={() => setIsSourceEditorOpen((current) => !current)}
              >
                <FiChevronDown
                  size={14}
                  className={cn('transition-transform duration-200', isSourceEditorOpen && 'rotate-180')}
                />
                {isSourceEditorOpen ? 'Hide source settings' : 'Edit source'}
              </Button>
            </div>

            {isSourceEditorOpen && (
              <div className="grid gap-3 border-t border-border/70 pt-4 xl:grid-cols-[18rem_minmax(0,1fr)_14rem]">
                <div className="space-y-2">
                  <span className="text-sm font-medium text-foreground">Source type</span>
                  <div className="grid grid-cols-3 gap-2">
                    {([
                      { key: 'repository', label: 'Repository' },
                      { key: 'directory', label: 'Local path' },
                      { key: 'sandbox', label: 'Sandbox path' },
                    ] as const).map((option) => (
                      <button
                        key={option.key}
                        type="button"
                        className={cn(
                          'rounded-2xl border px-3 py-3 text-sm font-medium transition-all duration-200',
                          sourceForm.marketplaceSourceType === option.key
                            ? 'border-primary/35 bg-primary/10 text-foreground shadow-glow'
                            : 'border-border/70 bg-card/60 text-muted-foreground hover:border-primary/20 hover:bg-card',
                        )}
                        onClick={() => setSourceForm((current) => ({
                          ...current,
                          marketplaceSourceType: option.key,
                        }))}
                      >
                        {option.label}
                      </button>
                    ))}
                  </div>
                </div>

                <div className="space-y-3">
                  {isPathSourceType(sourceForm.marketplaceSourceType) ? (
                    <div className="space-y-2">
                      <label htmlFor="skills-marketplace-directory" className="text-sm font-medium text-foreground">
                        {pathInputLabel(sourceForm.marketplaceSourceType)}
                      </label>
                      <InputGroup className="overflow-hidden">
                        <InputGroup.Text className="border-0 bg-muted/30">
                          <FiFolder size={16} aria-hidden="true" />
                        </InputGroup.Text>
                        <Form.Control
                          id="skills-marketplace-directory"
                          type="text"
                          placeholder={pathInputPlaceholder(sourceForm.marketplaceSourceType)}
                          value={sourceForm.marketplaceSourceType === 'sandbox'
                            ? sourceForm.marketplaceSandboxPath
                            : sourceForm.marketplaceRepositoryDirectory}
                          onChange={(event) => setSourceForm((current) => ({
                            ...current,
                            ...(current.marketplaceSourceType === 'sandbox'
                              ? { marketplaceSandboxPath: event.target.value }
                              : { marketplaceRepositoryDirectory: event.target.value }),
                          }))}
                          aria-label={pathInputLabel(sourceForm.marketplaceSourceType)}
                        />
                      </InputGroup>
                      {sourceForm.marketplaceSourceType === 'sandbox' && (
                        <p className="text-xs leading-5 text-muted-foreground">
                          Relative to the bot sandbox workspace.
                        </p>
                      )}
                    </div>
                  ) : (
                    <div className="space-y-2">
                      <label htmlFor="skills-marketplace-repository-url" className="text-sm font-medium text-foreground">
                        Repository URL
                      </label>
                      <Input
                        id="skills-marketplace-repository-url"
                        type="url"
                        placeholder={DEFAULT_SKILLS_REPOSITORY}
                        value={sourceForm.marketplaceRepositoryUrl}
                        onChange={(event) => setSourceForm((current) => ({
                          ...current,
                          marketplaceRepositoryUrl: event.target.value,
                        }))}
                      />
                    </div>
                  )}

                  <div className="rounded-2xl border border-border/70 bg-muted/20 px-4 py-3 text-sm leading-6 text-muted-foreground">
                    {sourceSummaryLabel(catalog)}
                  </div>
                </div>

                <div className="grid gap-3 sm:grid-cols-[minmax(0,1fr)_auto] xl:grid-cols-1">
                  <div className="space-y-2">
                    <label htmlFor="skills-marketplace-branch" className="text-sm font-medium text-foreground">
                      Branch
                    </label>
                    <Input
                      id="skills-marketplace-branch"
                      type="text"
                      value={sourceForm.marketplaceBranch}
                      onChange={(event) => setSourceForm((current) => ({
                        ...current,
                        marketplaceBranch: event.target.value,
                      }))}
                    />
                  </div>
                  <Button
                    type="button"
                    className="sm:self-end xl:w-full"
                    disabled={!isSourceDirty || updateSkillsMutation.isPending}
                    onClick={() => { void handleSaveSource(); }}
                  >
                    {updateSkillsMutation.isPending ? (
                      <span className="h-3.5 w-3.5 animate-spin rounded-full border-2 border-current border-r-transparent" />
                    ) : (
                      <FiSave size={14} />
                    )}
                    {updateSkillsMutation.isPending ? 'Saving...' : 'Save source'}
                  </Button>
                </div>
              </div>
            )}
          </div>
        </CardContent>
      </Card>

      <Card className="overflow-hidden border-primary/15 bg-[linear-gradient(135deg,rgba(14,165,233,0.08),rgba(15,23,42,0.02))]">
        <CardContent className="space-y-6 p-5">
          <div className="flex flex-col gap-5 xl:flex-row xl:items-start xl:justify-between">
            <div className="space-y-2">
              <div className="text-[0.72rem] font-semibold uppercase tracking-[0.28em] text-primary/80">
                Skill Library
              </div>
              <h2 className="text-2xl font-semibold tracking-tight text-foreground">Skills marketplace</h2>
              <p className="max-w-3xl text-sm leading-6 text-muted-foreground">
                Browse installable artifacts grouped by maintainer namespace. Artifacts may expose one skill or an entire pack.
              </p>
            </div>

            <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
              <StatCard label="Artifacts" value={catalog.items.length} />
              <StatCard label="Packs" value={packCount} />
              <StatCard label="Installed" value={installedCount} />
              <StatCard label="Updates" value={updatesCount} />
            </div>
          </div>

          <div className="grid gap-3 xl:grid-cols-[minmax(0,1fr)_auto] xl:items-end">
            <div className="space-y-2">
              <label htmlFor="skill-marketplace-search" className="text-sm font-medium text-foreground">
                Search artifacts
              </label>
              <div className="relative">
                <FiSearch className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-muted-foreground" size={16} />
                <Input
                  id="skill-marketplace-search"
                  type="text"
                  placeholder="Search by name, maintainer, or description"
                  value={searchQuery}
                  onChange={(event) => setSearchQuery(event.target.value)}
                  className="pl-11"
                />
              </div>
            </div>

            <div className="flex flex-wrap gap-2" role="tablist" aria-label="Skills marketplace filters">
              {([
                { key: 'all', label: 'All', count: catalog.items.length },
                { key: 'installed', label: 'Installed', count: installedCount },
                { key: 'updates', label: 'Updates', count: updatesCount },
              ] as const).map((entry) => (
                <button
                  key={entry.key}
                  type="button"
                  className={cn(
                    'inline-flex items-center gap-2 rounded-full border px-4 py-2 text-sm font-medium transition-all duration-200',
                    filter === entry.key
                      ? 'border-primary/35 bg-primary/10 text-foreground shadow-glow'
                      : 'border-border/70 bg-card/60 text-muted-foreground hover:border-primary/20 hover:bg-card',
                  )}
                  onClick={() => setFilter(entry.key)}
                  aria-pressed={filter === entry.key}
                >
                  <span>{entry.label}</span>
                  <Badge
                    variant={filter === entry.key ? 'light' : 'secondary'}
                    className="normal-case tracking-normal"
                  >
                    {entry.count}
                  </Badge>
                </button>
              ))}
            </div>
          </div>
        </CardContent>
      </Card>

      {!catalog.available && (
        <Alert variant="warning">
          {catalog.message ?? 'Skills marketplace is unavailable in this environment.'}
        </Alert>
      )}

      {catalog.available && items.length === 0 && (
        <Card>
          <CardContent className="flex flex-col items-center gap-3 py-12 text-center">
            <div className="inline-flex h-12 w-12 items-center justify-center rounded-2xl border border-border/70 bg-muted/20 text-muted-foreground">
              <FiPackage size={20} />
            </div>
            <div>
              <h3 className="text-base font-semibold text-foreground">No artifacts match this search</h3>
              <p className="mt-1 text-sm text-muted-foreground">Try another query or switch the active filter.</p>
            </div>
          </CardContent>
        </Card>
      )}

      {catalog.available && items.length > 0 && (
        <div className="grid gap-4 md:grid-cols-2 2xl:grid-cols-3">
          {items.map((item) => (
            <SkillMarketplaceCard
              key={item.id}
              item={item}
              isPending={installMutation.isPending}
              pendingSkillId={pendingSkillId}
              onInstall={(skill) => { void handleInstall(skill); }}
            />
          ))}
        </div>
      )}
    </div>
  );
}
