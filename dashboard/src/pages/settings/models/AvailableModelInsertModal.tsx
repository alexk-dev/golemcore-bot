import { type ReactElement, useDeferredValue, useState } from 'react';
import { FiArrowRight, FiSearch } from 'react-icons/fi';
import type { DiscoveredProviderModel } from '../../../api/models';
import { Alert } from '../../../components/ui/alert';
import { Badge } from '../../../components/ui/badge';
import { Modal } from '../../../components/ui/bootstrap-overlay';
import { Button } from '../../../components/ui/button';
import { Input } from '../../../components/ui/field';
import { useDiscoveredProviderModels } from '../../../hooks/useModels';
import { cn } from '../../../lib/utils';
import { extractErrorMessage } from '../../../utils/extractErrorMessage';
import type { ProviderProfileSummary } from './modelCatalogProviderProfiles';

interface AvailableModelInsertModalProps {
  providerProfiles: ProviderProfileSummary[];
  providerName: string;
  onHide: () => void;
  onSelectSuggestion: (suggestion: DiscoveredProviderModel) => void;
}

interface DiscoveredModelListProps {
  models: DiscoveredProviderModel[];
  onSelect: (suggestion: DiscoveredProviderModel) => void;
}

interface DiscoveryResultsProps {
  filteredModels: DiscoveredProviderModel[];
  isLoading: boolean;
  error: unknown;
  providerName: string;
  queryModelCount: number;
  search: string;
  onSearchChange: (value: string) => void;
  onSelectSuggestion: (suggestion: DiscoveredProviderModel) => void;
}

function getSelectedProviderProfile(
  providerProfiles: ProviderProfileSummary[],
  providerName: string,
): ProviderProfileSummary | null {
  return providerProfiles.find((profile) => profile.name === providerName) ?? null;
}

function filterDiscoveredModels(models: DiscoveredProviderModel[], query: string): DiscoveredProviderModel[] {
  const normalizedQuery = query.trim().toLowerCase();
  if (normalizedQuery.length === 0) {
    return models;
  }

  return models.filter((model) => (
    model.id.toLowerCase().includes(normalizedQuery)
    || model.displayName.toLowerCase().includes(normalizedQuery)
    || (model.ownedBy ?? '').toLowerCase().includes(normalizedQuery)
  ));
}

function DiscoveredModelList({
  models,
  onSelect,
}: DiscoveredModelListProps): ReactElement {
  return (
    <div className="grid max-h-[24rem] gap-3 overflow-y-auto pr-1">
      {models.map((model) => (
        <button
          key={`${model.provider}-${model.id}`}
          type="button"
          onClick={() => onSelect(model)}
          className={cn(
            'rounded-2xl border border-border/80 bg-card/70 px-4 py-4 text-left transition-all',
            'hover:border-primary/35 hover:bg-primary/5'
          )}
        >
          <div className="flex flex-wrap items-start justify-between gap-3">
            <div className="min-w-0 space-y-1">
              <div className="text-sm font-semibold text-foreground">{model.displayName}</div>
              <div className="font-mono text-xs text-muted-foreground">{model.id}</div>
            </div>

            <div className="flex shrink-0 items-center gap-2 text-primary">
              <span className="text-xs font-semibold uppercase tracking-[0.14em]">Insert</span>
              <FiArrowRight size={14} />
            </div>
          </div>

          <div className="mt-3 flex flex-wrap gap-2">
            <Badge variant="secondary">{model.provider}</Badge>
            {model.ownedBy != null && model.ownedBy.length > 0 && (
              <Badge variant="info">{model.ownedBy}</Badge>
            )}
          </div>
        </button>
      ))}
    </div>
  );
}

function DiscoveryResults({
  filteredModels,
  isLoading,
  error,
  providerName,
  queryModelCount,
  search,
  onSearchChange,
  onSelectSuggestion,
}: DiscoveryResultsProps): ReactElement {
  return (
    <>
      <div className="relative">
        <FiSearch className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-muted-foreground" size={15} />
        <Input
          value={search}
          onChange={(event) => onSearchChange(event.target.value)}
          placeholder={`Search ${providerName} models`}
          className="pl-11"
        />
      </div>

      {isLoading && (
        <Alert variant="secondary">Loading live models for `{providerName}`...</Alert>
      )}

      {error != null && (
        <Alert variant="danger">
          Failed to load models for `{providerName}`: {extractErrorMessage(error)}
        </Alert>
      )}

      {error == null && !isLoading && filteredModels.length === 0 && (
        <Alert variant="secondary">
          {queryModelCount > 0
            ? 'No discovered models match the current search.'
            : `The provider API returned no models for "${providerName}".`}
        </Alert>
      )}

      {filteredModels.length > 0 && (
        <DiscoveredModelList
          models={filteredModels}
          onSelect={onSelectSuggestion}
        />
      )}
    </>
  );
}

export function AvailableModelInsertModal({
  providerProfiles,
  providerName,
  onHide,
  onSelectSuggestion,
}: AvailableModelInsertModalProps): ReactElement {
  const selectedProvider = getSelectedProviderProfile(providerProfiles, providerName);
  const isProviderReady = selectedProvider?.isReady ?? false;
  const [search, setSearch] = useState('');
  const deferredSearch = useDeferredValue(search);
  const discoveryQuery = useDiscoveredProviderModels(providerName, isProviderReady);
  const filteredModels = filterDiscoveredModels(discoveryQuery.data ?? [], deferredSearch);
  const discoveredModelCount = discoveryQuery.data?.length ?? 0;

  return (
    <Modal show onHide={onHide} centered size="lg">
      <Modal.Header closeButton>
        <div className="space-y-1">
          <Modal.Title>Discover Models From Provider API</Modal.Title>
          <p className="text-sm text-muted-foreground">
            Showing only the live models returned by the currently selected provider profile.
          </p>
        </div>
      </Modal.Header>

      <Modal.Body className="space-y-5">
        <section className="space-y-3">
          <div className="rounded-2xl border border-border/80 bg-muted/20 px-4 py-4">
            <div className="flex flex-wrap items-center gap-2">
              <Badge variant="secondary">{providerName}</Badge>
              {selectedProvider?.apiType != null && (
                <Badge variant="secondary">{selectedProvider.apiType}</Badge>
              )}
              <Badge variant={isProviderReady ? 'success' : 'warning'}>
                {isProviderReady ? 'API ready' : 'API unavailable'}
              </Badge>
            </div>
            <p className="mt-3 text-sm text-muted-foreground">
              New catalog entries created from this dialog will stay attached to `{providerName}`.
            </p>
          </div>
        </section>

        <section className="space-y-3">
          <div className="space-y-1">
            <div className="text-xs font-semibold uppercase tracking-[0.16em] text-muted-foreground">Models</div>
            <p className="text-sm text-muted-foreground">
              Models are loaded from the selected provider API only.
            </p>
          </div>

          {selectedProvider == null ? (
            <Alert variant="warning">
              Provider profile `{providerName}` was not found in LLM Providers.
            </Alert>
          ) : !isProviderReady ? (
            <Alert variant="warning">
              Provider `{providerName}` is missing an API key or endpoint required for live discovery.
            </Alert>
          ) : (
            <DiscoveryResults
              filteredModels={filteredModels}
              isLoading={discoveryQuery.isLoading}
              error={discoveryQuery.error}
              providerName={providerName}
              queryModelCount={discoveredModelCount}
              search={search}
              onSearchChange={setSearch}
              onSelectSuggestion={onSelectSuggestion}
            />
          )}
        </section>
      </Modal.Body>

      <Modal.Footer>
        <Button variant="secondary" onClick={onHide}>
          Close
        </Button>
      </Modal.Footer>
    </Modal>
  );
}
