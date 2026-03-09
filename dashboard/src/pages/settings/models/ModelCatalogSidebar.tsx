import { type ReactElement, useDeferredValue, useState } from 'react';
import { FiCopy, FiPlus, FiRefreshCw, FiSearch } from 'react-icons/fi';
import { Badge } from '../../../components/ui/badge';
import { Button } from '../../../components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '../../../components/ui/card';
import { Input } from '../../../components/ui/field';
import { cn } from '../../../lib/utils';
import type { ProviderProfileSummary } from './modelCatalogProviderProfiles';
import type { CatalogModelItem, GroupedCatalogModels } from './modelCatalogTypes';

interface CatalogProviderOption {
  name: string;
  count: number;
  isReady: boolean;
}

interface ProviderOptionGridProps {
  providerOptions: CatalogProviderOption[];
  selectedProviderName: string;
  onSelectProvider: (providerName: string) => void;
}

interface ModelCatalogSidebarProps {
  groups: GroupedCatalogModels[];
  providerProfiles: ProviderProfileSummary[];
  selectedProviderName: string;
  selectedModelId: string | null;
  isReloading: boolean;
  onCreateNew: () => void;
  onOpenSuggestions: () => void;
  onReload: () => void;
  onSelectProvider: (providerName: string) => void;
  onSelectModel: (modelId: string) => void;
}

interface CatalogActionHintProps {
  hasSelectedProvider: boolean;
  canDiscoverModels: boolean;
}

interface CatalogListStateProps {
  filteredItems: CatalogModelItem[];
  selectedGroup: GroupedCatalogModels | null;
  selectedModelId: string | null;
  selectedProviderName: string;
  onSelectModel: (modelId: string) => void;
}

function filterItems(items: CatalogModelItem[], query: string): CatalogModelItem[] {
  const normalizedQuery = query.trim().toLowerCase();
  if (normalizedQuery.length === 0) {
    return items;
  }

  return items.filter((item) => {
    const displayName = item.settings.displayName ?? item.id;
    return item.id.toLowerCase().includes(normalizedQuery)
      || displayName.toLowerCase().includes(normalizedQuery);
  });
}

function buildProviderOptions(
  groups: GroupedCatalogModels[],
  providerProfiles: ProviderProfileSummary[],
): CatalogProviderOption[] {
  const counts = new Map(groups.map((group) => [group.provider, group.items.length]));
  return providerProfiles.map((profile) => ({
    name: profile.name,
    count: counts.get(profile.name) ?? 0,
    isReady: profile.isReady,
  }));
}

function getSelectedProviderOption(
  providerOptions: CatalogProviderOption[],
  selectedProviderName: string,
): CatalogProviderOption | null {
  return providerOptions.find((provider) => provider.name === selectedProviderName) ?? null;
}

function getProviderBadgeVariant(provider: CatalogProviderOption): 'secondary' | 'success' | 'warning' {
  if (provider.isReady) {
    return 'success';
  }
  return 'secondary';
}

function getProviderBadgeLabel(provider: CatalogProviderOption): string {
  if (provider.isReady) {
    return 'Ready';
  }
  return 'Configured';
}

function CatalogActionHint({
  hasSelectedProvider,
  canDiscoverModels,
}: CatalogActionHintProps): ReactElement | null {
  if (!hasSelectedProvider) {
    return (
      <p className="text-xs text-muted-foreground">
        Select a provider first to create a model or load its live API catalog.
      </p>
    );
  }

  if (canDiscoverModels) {
    return null;
  }

  return (
    <p className="text-xs text-muted-foreground">
      Live discovery is available only for provider profiles with a configured API key.
    </p>
  );
}

function CatalogListState({
  filteredItems,
  selectedGroup,
  selectedModelId,
  selectedProviderName,
  onSelectModel,
}: CatalogListStateProps): ReactElement {
  if (selectedProviderName.length === 0) {
    return (
      <div className="rounded-2xl border border-dashed border-border/80 bg-muted/30 px-4 py-6 text-sm text-muted-foreground">
        Select a provider to show only its catalog models.
      </div>
    );
  }

  if (selectedGroup == null) {
    return (
      <div className="rounded-2xl border border-dashed border-border/80 bg-muted/30 px-4 py-6 text-sm text-muted-foreground">
        No catalog models are defined yet for `{selectedProviderName}`.
      </div>
    );
  }

  if (filteredItems.length === 0) {
    return (
      <div className="rounded-2xl border border-dashed border-border/80 bg-muted/30 px-4 py-6 text-sm text-muted-foreground">
        No models match the current filter.
      </div>
    );
  }

  return (
    <section className="space-y-2">
      <div className="flex items-center justify-between">
        <h3 className="text-xs font-semibold uppercase tracking-[0.18em] text-muted-foreground">
          {selectedProviderName}
        </h3>
        <span className="text-xs text-muted-foreground">{filteredItems.length}</span>
      </div>

      <div className="space-y-2">
        {filteredItems.map((item) => {
          const isActive = item.id === selectedModelId;
          return (
            <button
              key={item.id}
              type="button"
              onClick={() => onSelectModel(item.id)}
              className={cn(
                'w-full rounded-2xl border px-4 py-3 text-left transition-all',
                'hover:border-primary/35 hover:bg-primary/5',
                isActive
                  ? 'border-primary/50 bg-primary/10 shadow-soft'
                  : 'border-border/80 bg-card/60'
              )}
            >
              <div className="flex items-start justify-between gap-3">
                <div className="min-w-0">
                  <div className="truncate text-sm font-semibold text-foreground">
                    {item.settings.displayName ?? item.id}
                  </div>
                  <div className="mt-1 truncate font-mono text-xs text-muted-foreground">
                    {item.id}
                  </div>
                </div>
                <div className="flex shrink-0 flex-wrap justify-end gap-1">
                  <Badge variant={item.settings.supportsVision ? 'info' : 'secondary'}>
                    {item.settings.supportsVision ? 'Vision' : 'Text'}
                  </Badge>
                  {item.settings.reasoning != null && (
                    <Badge variant="warning">Reasoning</Badge>
                  )}
                </div>
              </div>
            </button>
          );
        })}
      </div>
    </section>
  );
}

function ProviderOptionGrid({
  providerOptions,
  selectedProviderName,
  onSelectProvider,
}: ProviderOptionGridProps): ReactElement {
  return (
    <div className="grid gap-2">
      {providerOptions.map((provider) => {
        const isActive = provider.name === selectedProviderName;
        return (
          <button
            key={provider.name}
            type="button"
            onClick={() => onSelectProvider(provider.name)}
            className={cn(
              'rounded-2xl border px-4 py-3 text-left transition-all',
              'hover:border-primary/35 hover:bg-primary/5',
              isActive ? 'border-primary/50 bg-primary/10 shadow-soft' : 'border-border/80 bg-card/60'
            )}
          >
            <div className="flex items-start justify-between gap-3">
              <div className="min-w-0">
                <div className="truncate text-sm font-semibold text-foreground">{provider.name}</div>
                <div className="mt-1 text-xs text-muted-foreground">
                  {provider.count} model{provider.count === 1 ? '' : 's'}
                </div>
              </div>
              <Badge variant={getProviderBadgeVariant(provider)}>
                {getProviderBadgeLabel(provider)}
              </Badge>
            </div>
          </button>
        );
      })}
    </div>
  );
}

export function ModelCatalogSidebar({
  groups,
  providerProfiles,
  selectedProviderName,
  selectedModelId,
  isReloading,
  onCreateNew,
  onOpenSuggestions,
  onReload,
  onSelectProvider,
  onSelectModel,
}: ModelCatalogSidebarProps): ReactElement {
  const [search, setSearch] = useState('');
  const deferredSearch = useDeferredValue(search);
  const providerOptions = buildProviderOptions(groups, providerProfiles);
  const selectedProvider = getSelectedProviderOption(providerOptions, selectedProviderName);
  const selectedGroup = groups.find((group) => group.provider === selectedProviderName) ?? null;
  const filteredItems = selectedGroup != null ? filterItems(selectedGroup.items, deferredSearch) : [];
  const hasSelectedProvider = selectedProvider != null;
  const canCreateNewModel = selectedProvider != null;
  const canDiscoverModels = selectedProvider?.isReady ?? false;
  const searchPlaceholder = hasSelectedProvider ? `Search ${selectedProviderName} models` : 'Select provider first';

  return (
    <Card className="h-full">
      <CardHeader className="items-start">
        <div className="space-y-1">
          <CardTitle>Catalog</CardTitle>
          <p className="text-sm text-muted-foreground">
            Choose a provider first, then work only with models from that provider.
          </p>
        </div>
        <Badge variant="secondary">{providerOptions.length} providers</Badge>
      </CardHeader>
      <CardContent className="space-y-4">
        <div className="flex flex-wrap gap-2">
          <Button onClick={onCreateNew} disabled={!canCreateNewModel}>
            <FiPlus size={15} />
            New Model
          </Button>
          <Button variant="secondary" onClick={onOpenSuggestions} disabled={!canDiscoverModels}>
            <FiCopy size={15} />
            Discover from API
          </Button>
          <Button variant="secondary" onClick={onReload} disabled={isReloading}>
            <FiRefreshCw size={15} className={cn(isReloading && 'animate-spin')} />
            {isReloading ? 'Reloading...' : 'Reload'}
          </Button>
        </div>
        <CatalogActionHint
          hasSelectedProvider={hasSelectedProvider}
          canDiscoverModels={canDiscoverModels}
        />

        <div className="space-y-2">
          <div className="text-xs font-semibold uppercase tracking-[0.16em] text-muted-foreground">
            Providers
          </div>
          <ProviderOptionGrid
            providerOptions={providerOptions}
            selectedProviderName={selectedProviderName}
            onSelectProvider={onSelectProvider}
          />
        </div>

        <div className="relative">
          <FiSearch className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-muted-foreground" size={15} />
          <Input
            value={search}
            onChange={(event) => setSearch(event.target.value)}
            placeholder={searchPlaceholder}
            className="pl-9"
            disabled={!hasSelectedProvider}
          />
        </div>

        <div className="space-y-4">
          <CatalogListState
            filteredItems={filteredItems}
            selectedGroup={selectedGroup}
            selectedModelId={selectedModelId}
            selectedProviderName={selectedProviderName}
            onSelectModel={onSelectModel}
          />
        </div>
      </CardContent>
    </Card>
  );
}
