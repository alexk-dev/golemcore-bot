import { type ReactElement, useEffect, useState } from 'react';
import toast from 'react-hot-toast';
import { FiSave } from 'react-icons/fi';
import type { ModelRegistryConfig } from '../../../api/settings';
import { SaveStateHint } from '../../../components/common/SettingsSaveBar';
import { Badge } from '../../../components/ui/badge';
import { Button } from '../../../components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '../../../components/ui/card';
import { Input } from '../../../components/ui/field';
import { extractErrorMessage } from '../../../utils/extractErrorMessage';

interface ModelRegistrySourceCardProps {
  config: ModelRegistryConfig;
  isSaving: boolean;
  onSave: (config: ModelRegistryConfig) => Promise<void>;
}

interface ModelRegistryDraft {
  repositoryUrl: string;
  branch: string;
}

function toDraft(config: ModelRegistryConfig): ModelRegistryDraft {
  return {
    repositoryUrl: config.repositoryUrl ?? '',
    branch: config.branch ?? 'main',
  };
}

function toConfig(draft: ModelRegistryDraft): ModelRegistryConfig {
  const repositoryUrl = draft.repositoryUrl.trim();
  const branch = draft.branch.trim();
  return {
    repositoryUrl: repositoryUrl.length > 0 ? repositoryUrl : null,
    branch: branch.length > 0 ? branch : 'main',
  };
}

function isDirtyDraft(draft: ModelRegistryDraft, config: ModelRegistryConfig): boolean {
  const nextConfig = toConfig(draft);
  return nextConfig.repositoryUrl !== (config.repositoryUrl ?? null)
    || nextConfig.branch !== (config.branch ?? 'main');
}

export function ModelRegistrySourceCard({
  config,
  isSaving,
  onSave,
}: ModelRegistrySourceCardProps): ReactElement {
  const [draft, setDraft] = useState<ModelRegistryDraft>(() => toDraft(config));
  const isConfigured = (config.repositoryUrl ?? '').trim().length > 0;
  const isDirty = isDirtyDraft(draft, config);

  // Keep the local form aligned with persisted runtime-config updates after save or refetch.
  useEffect(() => {
    setDraft({
      repositoryUrl: config.repositoryUrl ?? '',
      branch: config.branch ?? 'main',
    });
  }, [config.branch, config.repositoryUrl]);

  async function handleSave(): Promise<void> {
    try {
      await onSave(toConfig(draft));
      toast.success('Model registry source saved');
    } catch (error) {
      toast.error(extractErrorMessage(error));
    }
  }

  return (
    <Card className="settings-card">
      <CardHeader className="items-start">
        <div className="space-y-1">
          <CardTitle>Model Registry Source</CardTitle>
          <CardDescription>
            Select a discovered model to resolve registry defaults on demand.
          </CardDescription>
        </div>

        <div className="flex flex-wrap gap-2">
          <Badge variant={isConfigured ? 'success' : 'secondary'}>
            {isConfigured ? 'Configured' : 'Not configured'}
          </Badge>
          <Badge variant="info">24h cache TTL</Badge>
          <Badge variant="secondary">GitHub raw lookup</Badge>
        </div>
      </CardHeader>

      <CardContent className="space-y-4">
        <div className="grid gap-4 lg:grid-cols-[minmax(0,1fr)_14rem]">
          <label className="space-y-2">
            <span className="text-xs font-semibold uppercase tracking-[0.16em] text-muted-foreground">
              Repository URL
            </span>
            <Input
              value={draft.repositoryUrl}
              placeholder="https://github.com/alexk-dev/golemcore-models"
              onChange={(event) => setDraft({ ...draft, repositoryUrl: event.target.value })}
            />
          </label>

          <label className="space-y-2">
            <span className="text-xs font-semibold uppercase tracking-[0.16em] text-muted-foreground">
              Branch
            </span>
            <Input
              value={draft.branch}
              placeholder="main"
              onChange={(event) => setDraft({ ...draft, branch: event.target.value })}
            />
          </label>
        </div>

        <div className="rounded-2xl border border-border/80 bg-muted/20 px-4 py-3">
          <div className="text-sm text-muted-foreground">
            Provider-scoped configs resolve before shared ones, and stale cache can still satisfy inserts when GitHub is unavailable.
          </div>
        </div>

        <div className="flex flex-wrap items-center justify-between gap-3 rounded-2xl border border-border/80 bg-muted/20 px-4 py-3">
          <SaveStateHint isDirty={isDirty} />
          <Button
            onClick={() => {
              void handleSave();
            }}
            disabled={!isDirty || isSaving}
          >
            <FiSave size={15} />
            {isSaving ? 'Saving...' : 'Save Source'}
          </Button>
        </div>
      </CardContent>
    </Card>
  );
}
