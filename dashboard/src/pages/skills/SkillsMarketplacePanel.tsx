import { type ReactElement, useDeferredValue, useEffect, useMemo, useRef, useState } from 'react';
import toast from 'react-hot-toast';
import type { SkillMarketplaceItem } from '../../api/skills';
import { useInstallSkillFromMarketplace, useSkillMarketplace } from '../../hooks/useSkills';
import { useRuntimeConfig, useUpdateSkills } from '../../hooks/useSettings';
import { extractErrorMessage } from '../../utils/extractErrorMessage';
import { SkillMarketplaceCard } from './SkillMarketplaceCard';
import {
  buildMarketplaceStats,
  buildSourceForm,
  type MarketplaceFilter,
  type MarketplaceSourceForm,
  matchesFilter,
  matchesSearch,
  normalizeSourceForm,
} from './skillsMarketplacePanelUtils';
import {
  SkillsMarketplaceEmptyState,
  SkillsMarketplaceHeroCard,
  SkillsMarketplaceLoadingState,
  SkillsMarketplaceSourceCard,
  SkillsMarketplaceUnavailableState,
} from './SkillsMarketplacePanelParts';

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
    const normalizedSourceForm = normalizeSourceForm(sourceForm);
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
    return <SkillsMarketplaceLoadingState />;
  }

  if (marketplaceQuery.isError || marketplaceQuery.data == null) {
    return <SkillsMarketplaceUnavailableState message="Unable to load skills marketplace metadata from the backend." />;
  }

  const catalog = marketplaceQuery.data;
  const items = catalog.items.filter((item) => matchesFilter(item, filter) && matchesSearch(item, deferredSearch));
  const pendingSkillId = installMutation.isPending ? installMutation.variables?.skillId ?? null : null;
  const { installedCount, updatesCount, packCount } = buildMarketplaceStats(catalog.items);

  return (
    <div className="space-y-4">
      <SkillsMarketplaceSourceCard
        catalog={catalog}
        sourceForm={sourceForm}
        isSourceDirty={isSourceDirty}
        isSourceEditorOpen={isSourceEditorOpen}
        isSaving={updateSkillsMutation.isPending}
        onToggleEditor={() => setIsSourceEditorOpen((current) => !current)}
        onSourceTypeChange={(marketplaceSourceType) => setSourceForm((current) => ({ ...current, marketplaceSourceType }))}
        onPathChange={(value) => setSourceForm((current) => ({
          ...current,
          ...(current.marketplaceSourceType === 'sandbox'
            ? { marketplaceSandboxPath: value }
            : { marketplaceRepositoryDirectory: value }),
        }))}
        onRepositoryUrlChange={(marketplaceRepositoryUrl) => setSourceForm((current) => ({ ...current, marketplaceRepositoryUrl }))}
        onBranchChange={(marketplaceBranch) => setSourceForm((current) => ({ ...current, marketplaceBranch }))}
        onSave={() => { void handleSaveSource(); }}
      />

      <SkillsMarketplaceHeroCard
        totalArtifacts={catalog.items.length}
        packCount={packCount}
        installedCount={installedCount}
        updatesCount={updatesCount}
        searchQuery={searchQuery}
        filter={filter}
        onSearchChange={setSearchQuery}
        onFilterChange={setFilter}
      />

      {!catalog.available && (
        <SkillsMarketplaceUnavailableState message={catalog.message} />
      )}

      {catalog.available && items.length === 0 && (
        <SkillsMarketplaceEmptyState />
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
