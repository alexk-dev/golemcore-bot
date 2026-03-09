import { type ReactElement, useMemo } from 'react';
import { useNavigate } from 'react-router-dom';
import { Alert } from '../../components/ui/alert';
import { Badge } from '../../components/ui/badge';
import { Button } from '../../components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '../../components/ui/card';
import type { LlmConfig } from '../../api/settings';
import { ModelCatalogEditor } from './models/ModelCatalogEditor';
import { getProviderProfileSummaries } from './models/modelCatalogProviderProfiles';

interface ModelCatalogTabProps {
  llmConfig: LlmConfig;
}

export function ModelCatalogTab({ llmConfig }: ModelCatalogTabProps): ReactElement {
  const navigate = useNavigate();
  const providerProfiles = useMemo(() => getProviderProfileSummaries(llmConfig), [llmConfig]);
  const readyProfilesCount = providerProfiles.filter((profile) => profile.isReady).length;

  return (
    <div className="space-y-4">
      <Card className="settings-card">
        <CardHeader className="items-start">
          <div className="space-y-1">
            <CardTitle>Provider Profiles</CardTitle>
            <CardDescription>
              Model catalog entries should map to configured LLM provider profiles. Live suggestions are fetched on demand from
              the selected provider API.
            </CardDescription>
          </div>

          <div className="flex flex-wrap gap-2">
            <Badge variant="secondary">{providerProfiles.length} profiles</Badge>
            <Badge variant={readyProfilesCount > 0 ? 'success' : 'warning'}>
              {readyProfilesCount} API-ready
            </Badge>
            <Badge variant="info">Provider-first discovery</Badge>
          </div>
        </CardHeader>

        <CardContent className="space-y-4">
          {providerProfiles.length === 0 ? (
            <Alert variant="warning" className="space-y-3">
              <div>No LLM provider profiles are configured yet. Add profiles first so catalog entries and API suggestions can bind to them.</div>
              <div>
                <Button type="button" variant="secondary" onClick={() => navigate('/settings/llm-providers')}>
                  Open LLM Providers
                </Button>
              </div>
            </Alert>
          ) : (
            <>
              <div className="grid gap-3 md:grid-cols-3">
                <div className="rounded-2xl border border-border/80 bg-muted/20 px-4 py-3">
                  <div className="text-xs font-semibold uppercase tracking-[0.16em] text-muted-foreground">Configured</div>
                  <div className="mt-2 text-2xl font-semibold text-foreground">{providerProfiles.length}</div>
                  <div className="mt-1 text-sm text-muted-foreground">Profiles available for catalog mapping</div>
                </div>
                <div className="rounded-2xl border border-border/80 bg-muted/20 px-4 py-3">
                  <div className="text-xs font-semibold uppercase tracking-[0.16em] text-muted-foreground">API Ready</div>
                  <div className="mt-2 text-2xl font-semibold text-foreground">{readyProfilesCount}</div>
                  <div className="mt-1 text-sm text-muted-foreground">Profiles with a configured API key</div>
                </div>
                <div className="rounded-2xl border border-border/80 bg-muted/20 px-4 py-3">
                  <div className="text-xs font-semibold uppercase tracking-[0.16em] text-muted-foreground">Discovery Flow</div>
                  <div className="mt-2 text-2xl font-semibold text-foreground">2 steps</div>
                  <div className="mt-1 text-sm text-muted-foreground">Choose provider first, then fetch live models</div>
                </div>
              </div>

              <div className="flex flex-wrap gap-2">
                {providerProfiles.map((profile) => (
                  <Badge key={profile.name} variant={profile.isReady ? 'success' : 'secondary'}>
                    {profile.name}
                    {profile.apiType != null ? ` · ${profile.apiType}` : ''}
                  </Badge>
                ))}
              </div>
            </>
          )}
        </CardContent>
      </Card>

      <ModelCatalogEditor providerProfiles={providerProfiles} />
    </div>
  );
}
