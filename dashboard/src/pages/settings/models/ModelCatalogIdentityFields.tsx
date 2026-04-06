import type { ReactElement } from 'react';
import { Badge } from '../../../components/ui/badge';
import { Input } from '../../../components/ui/field';
import { Form } from '../../../components/ui/bootstrap-form';
import type { ProviderProfileSummary } from './modelCatalogProviderProfiles';
import type { ModelDraft } from './modelCatalogTypes';

type ProviderOption = ProviderProfileSummary;

interface ProviderStatusMeta {
  badgeLabel: string;
  badgeVariant: 'secondary' | 'success' | 'warning';
}

interface ModelCatalogIdentityFieldsProps {
  draft: ModelDraft;
  isExisting: boolean;
  providerProfiles: ProviderProfileSummary[];
  onDraftChange: (draft: ModelDraft) => void;
}

function buildProviderOptions(providerProfiles: ProviderProfileSummary[]): ProviderOption[] {
  return providerProfiles;
}

function getSelectedProviderOption(providerOptions: ProviderOption[], currentProvider: string): ProviderOption | null {
  return providerOptions.find((option) => option.name === currentProvider) ?? null;
}

function getProviderStatusMeta(provider: ProviderOption): ProviderStatusMeta {
  if (provider.isReady) {
    return {
      badgeLabel: 'Profile ready',
      badgeVariant: 'success',
    };
  }
  return {
    badgeLabel: 'Profile configured',
    badgeVariant: 'secondary',
  };
}

export function ModelCatalogIdentityFields({
  draft,
  isExisting,
  providerProfiles,
  onDraftChange,
}: ModelCatalogIdentityFieldsProps): ReactElement {
  const providerOptions = buildProviderOptions(providerProfiles);
  const selectedProvider = getSelectedProviderOption(providerOptions, draft.provider);
  const providerStatus = selectedProvider != null ? getProviderStatusMeta(selectedProvider) : null;
  const providerValue = selectedProvider != null ? draft.provider : '';

  return (
    <div className="grid gap-4 xl:grid-cols-2">
      <div className="space-y-2">
        <label className="text-xs font-semibold uppercase tracking-[0.16em] text-muted-foreground">
          Model ID
        </label>
        <Input
          value={draft.id}
          onChange={(event) => onDraftChange({ ...draft, id: event.target.value })}
          disabled={isExisting}
          placeholder="gpt-4.1-mini"
        />
        <p className="text-sm text-muted-foreground">
          {isExisting ? 'To rename a model, create a new entry and delete the old one.' : 'Use the raw model slug that will appear in routing.'}
        </p>
      </div>

      <div className="space-y-2">
        <label className="text-xs font-semibold uppercase tracking-[0.16em] text-muted-foreground">
          Provider
        </label>
        <Form.Select
          value={providerValue}
          onChange={(event) => onDraftChange({ ...draft, provider: event.target.value })}
          disabled={providerOptions.length === 0}
        >
          {providerOptions.length === 0 && (
            <option value="">No provider profiles configured</option>
          )}
          {providerOptions.length > 0 && providerValue.length === 0 && (
            <option value="">Select provider profile</option>
          )}
          {providerOptions.map((provider) => (
            <option key={provider.name} value={provider.name}>
              {provider.name}
            </option>
          ))}
        </Form.Select>
        <div className="flex flex-wrap gap-2">
          {selectedProvider == null ? (
            <Badge variant={providerProfiles.length > 0 ? 'warning' : 'secondary'}>
              {providerProfiles.length > 0 ? 'Provider profile required' : 'Add provider profile first'}
            </Badge>
          ) : providerStatus != null && (
            <>
              <Badge variant={providerStatus.badgeVariant}>
                {providerStatus.badgeLabel}
              </Badge>
              {selectedProvider.apiType != null && (
                <Badge variant="secondary">{selectedProvider.apiType}</Badge>
              )}
            </>
          )}
        </div>
        <p className="text-sm text-muted-foreground">
          Provider selection is driven by LLM provider profiles. API suggestions only include models returned for those profiles.
        </p>
      </div>

      <div className="space-y-2">
        <label className="text-xs font-semibold uppercase tracking-[0.16em] text-muted-foreground">
          Display Name
        </label>
        <Input
          value={draft.displayName}
          onChange={(event) => onDraftChange({ ...draft, displayName: event.target.value })}
          placeholder="GPT-4.1 Mini"
        />
        <p className="text-sm text-muted-foreground">
          Optional human label for the dashboard and command output.
        </p>
      </div>

      <div className="space-y-2">
        <label className="text-xs font-semibold uppercase tracking-[0.16em] text-muted-foreground">
          Max Input Tokens
        </label>
        <Input
          value={draft.maxInputTokens}
          onChange={(event) => onDraftChange({ ...draft, maxInputTokens: event.target.value })}
          inputMode="numeric"
          placeholder="128000"
        />
        <p className="text-sm text-muted-foreground">
          Used for truncation and compaction thresholds when no reasoning override applies.
        </p>
      </div>
    </div>
  );
}
