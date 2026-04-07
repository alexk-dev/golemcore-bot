import { type ReactElement, startTransition, useEffect, useState } from 'react';
import toast from 'react-hot-toast';
import type { DiscoveredProviderModel } from '../../../api/models';
import { Alert } from '../../../components/ui/alert';
import { Card, CardContent } from '../../../components/ui/card';
import { Skeleton } from '../../../components/ui/skeleton';
import { extractErrorMessage } from '../../../utils/extractErrorMessage';
import {
  useDeleteModel,
  useModelsConfig,
  useReloadModels,
  useResolveModelRegistry,
  useSaveModel,
} from '../../../hooks/useModels';
import { useTelemetry } from '../../../lib/telemetry/TelemetryProvider';
import { AvailableModelInsertModal } from './AvailableModelInsertModal';
import { ModelCatalogForm } from './ModelCatalogForm';
import { ModelCatalogSidebar } from './ModelCatalogSidebar';
import type { ProviderProfileSummary } from './modelCatalogProviderProfiles';
import {
  createDraftFromSuggestion,
  createEmptyModelDraft,
  getGroupedCatalogModels,
  isModelDraftDirty,
  toModelDraft,
  toModelSettings,
  validateModelDraft,
} from './modelCatalogTypes';

const NEW_MODEL_SELECTION = '__new__';

interface ModelCatalogEditorProps {
  providerProfiles: ProviderProfileSummary[];
}

interface ModelCatalogEditorBodyProps {
  groupedModels: ReturnType<typeof getGroupedCatalogModels>;
  providerProfiles: ProviderProfileSummary[];
  selectedProviderName: string;
  selectedModelId: string | null;
  isReloading: boolean;
  draft: ReturnType<typeof createEmptyModelDraft>;
  isDirty: boolean;
  isExisting: boolean;
  isSaving: boolean;
  isDeleting: boolean;
  onCreateNew: () => void;
  onOpenSuggestions: () => void;
  onReload: () => void;
  onSelectProvider: (providerName: string) => void;
  onSelectModel: (modelId: string) => void;
  onDraftChange: (draft: ReturnType<typeof createEmptyModelDraft>) => void;
  onSave: () => void;
  onDelete: () => void;
}

function ModelCatalogEditorLoadingState(): ReactElement {
  return (
    <div className="grid gap-4 xl:grid-cols-[minmax(20rem,24rem)_1fr]">
      <Card>
        <CardContent className="space-y-4">
          <Skeleton className="h-10 w-40" />
          <Skeleton className="h-10 w-full" />
          <Skeleton className="h-28 w-full" />
          <Skeleton className="h-28 w-full" />
        </CardContent>
      </Card>
      <Card>
        <CardContent className="space-y-4">
          <Skeleton className="h-10 w-56" />
          <Skeleton className="h-28 w-full" />
          <Skeleton className="h-24 w-full" />
          <Skeleton className="h-48 w-full" />
        </CardContent>
      </Card>
    </div>
  );
}

function ModelCatalogEditorBody({
  groupedModels,
  providerProfiles,
  selectedProviderName,
  selectedModelId,
  isReloading,
  draft,
  isDirty,
  isExisting,
  isSaving,
  isDeleting,
  onCreateNew,
  onOpenSuggestions,
  onReload,
  onSelectProvider,
  onSelectModel,
  onDraftChange,
  onSave,
  onDelete,
}: ModelCatalogEditorBodyProps): ReactElement {
  return (
    <div className="grid gap-4 xl:grid-cols-[minmax(20rem,24rem)_1fr]">
      <ModelCatalogSidebar
        groups={groupedModels}
        providerProfiles={providerProfiles}
        selectedProviderName={selectedProviderName}
        selectedModelId={isExisting ? selectedModelId : null}
        isReloading={isReloading}
        onCreateNew={onCreateNew}
        onOpenSuggestions={onOpenSuggestions}
        onReload={onReload}
        onSelectProvider={onSelectProvider}
        onSelectModel={onSelectModel}
      />

      <ModelCatalogForm
        draft={draft}
        isDirty={isDirty}
        isExisting={isExisting}
        isSaving={isSaving}
        isDeleting={isDeleting}
        providerProfiles={providerProfiles}
        onDraftChange={onDraftChange}
        onSave={onSave}
        onDelete={onDelete}
      />
    </div>
  );
}

export function ModelCatalogEditor({ providerProfiles }: ModelCatalogEditorProps): ReactElement {
  const telemetry = useTelemetry();
  const {
    data: modelsConfig,
    isLoading: modelsLoading,
    error: modelsError,
    refetch: refetchModelsConfig,
  } = useModelsConfig();
  const saveModel = useSaveModel();
  const deleteModel = useDeleteModel();
  const reloadModels = useReloadModels();
  const resolveModelRegistry = useResolveModelRegistry();
  const [selectedProviderName, setSelectedProviderName] = useState('');
  const [selectedModelId, setSelectedModelId] = useState(NEW_MODEL_SELECTION);
  const [draft, setDraft] = useState(createEmptyModelDraft(''));
  const [isInsertModalOpen, setIsInsertModalOpen] = useState(false);

  // Keep the editor draft aligned with the selected persisted model after reloads or saves.
  useEffect(() => {
    if (modelsConfig == null || selectedModelId === NEW_MODEL_SELECTION) {
      return;
    }

    const selectedSettings = modelsConfig.models[selectedModelId];
    if (selectedSettings != null) {
      setSelectedProviderName(selectedSettings.provider);
      setDraft(toModelDraft(selectedModelId, selectedSettings));
      return;
    }

    setSelectedModelId(NEW_MODEL_SELECTION);
    setDraft(createEmptyModelDraft(selectedProviderName));
  }, [modelsConfig, selectedModelId, selectedProviderName]);

  const groupedModels = getGroupedCatalogModels(
    modelsConfig,
    providerProfiles.map((profile) => profile.name),
  );
  const isExisting = selectedModelId !== NEW_MODEL_SELECTION;
  const isDirty = isModelDraftDirty(draft, isExisting ? selectedModelId : null, modelsConfig);

  async function handleSave(): Promise<void> {
    const validationError = validateModelDraft(draft, modelsConfig?.models ?? {}, isExisting ? selectedModelId : null);
    if (validationError != null) {
      toast.error(validationError);
      return;
    }

    const targetId = draft.id.trim();
    try {
      await saveModel.mutateAsync({ id: targetId, settings: toModelSettings(draft) });
      await refetchModelsConfig();
      telemetry.recordCounter('model_catalog_edit_count');
      startTransition(() => {
        setSelectedModelId(targetId);
      });
      toast.success(isExisting ? 'Model updated' : 'Model created');
    } catch (error) {
      toast.error(extractErrorMessage(error));
    }
  }

  async function handleDelete(): Promise<void> {
    if (!isExisting) {
      return;
    }

    try {
      await deleteModel.mutateAsync(selectedModelId);
      await refetchModelsConfig();
      startTransition(() => {
        setSelectedModelId(NEW_MODEL_SELECTION);
        setDraft(createEmptyModelDraft(selectedProviderName));
      });
      toast.success('Model deleted');
    } catch (error) {
      toast.error(extractErrorMessage(error));
    }
  }

  async function handleReload(): Promise<void> {
    try {
      await reloadModels.mutateAsync();
      await refetchModelsConfig();
      telemetry.recordCounter('model_reload_count');
      toast.success('Model catalog reloaded');
    } catch (error) {
      toast.error(extractErrorMessage(error));
    }
  }

  function handleSelectModel(modelId: string): void {
    const selectedSettings = modelsConfig?.models[modelId];
    if (selectedSettings == null) {
      return;
    }
    startTransition(() => {
      setSelectedProviderName(selectedSettings.provider);
      setSelectedModelId(modelId);
      setDraft(toModelDraft(modelId, selectedSettings));
    });
  }

  function handleCreateNew(): void {
    startTransition(() => {
      setSelectedModelId(NEW_MODEL_SELECTION);
      setDraft(createEmptyModelDraft(selectedProviderName));
    });
  }

  async function handleSuggestionSelect(suggestion: DiscoveredProviderModel): Promise<void> {
    try {
      const resolvedRegistry = suggestion.defaultSettings != null
        ? { defaultSettings: suggestion.defaultSettings, configSource: 'provider' as const, cacheStatus: 'remote-hit' as const }
        : await resolveModelRegistry.mutateAsync({
          provider: suggestion.provider,
          modelId: suggestion.id,
        });
      const nextDraft = createDraftFromSuggestion(
        suggestion,
        modelsConfig,
        resolvedRegistry.defaultSettings,
      );
      const existingModel = modelsConfig?.models[nextDraft.id];
      startTransition(() => {
        setSelectedProviderName(suggestion.provider);
        setSelectedModelId(existingModel != null ? nextDraft.id : NEW_MODEL_SELECTION);
        setDraft(nextDraft);
        setIsInsertModalOpen(false);
      });
    } catch (error) {
      toast.error(extractErrorMessage(error));
    }
  }

  function handleSelectProvider(providerName: string): void {
    const selectedSettings = isExisting ? modelsConfig?.models[selectedModelId] : null;
    startTransition(() => {
      setSelectedProviderName(providerName);
      setSelectedModelId(selectedSettings?.provider === providerName && isExisting ? selectedModelId : NEW_MODEL_SELECTION);
      setDraft(
        selectedSettings?.provider === providerName && isExisting
          ? toModelDraft(selectedModelId, selectedSettings)
          : createEmptyModelDraft(providerName)
      );
    });
  }

  if (modelsLoading) {
    return <ModelCatalogEditorLoadingState />;
  }

  if (modelsError != null) {
    return (
      <Alert variant="danger">
        Failed to load model catalog: {extractErrorMessage(modelsError)}
      </Alert>
    );
  }

  return (
    <>
      <ModelCatalogEditorBody
        groupedModels={groupedModels}
        providerProfiles={providerProfiles}
        selectedProviderName={selectedProviderName}
        selectedModelId={selectedModelId}
        isReloading={reloadModels.isPending}
        draft={draft}
        isDirty={isDirty}
        isExisting={isExisting}
        isSaving={saveModel.isPending}
        isDeleting={deleteModel.isPending}
        onCreateNew={handleCreateNew}
        onOpenSuggestions={() => setIsInsertModalOpen(true)}
        onReload={() => { void handleReload(); }}
        onSelectProvider={handleSelectProvider}
        onSelectModel={handleSelectModel}
        onDraftChange={setDraft}
        onSave={() => { void handleSave(); }}
        onDelete={() => { void handleDelete(); }}
      />

      {isInsertModalOpen && (
        <AvailableModelInsertModal
          providerProfiles={providerProfiles}
          providerName={selectedProviderName}
          isSelectingSuggestion={resolveModelRegistry.isPending}
          onHide={() => setIsInsertModalOpen(false)}
          onSelectSuggestion={handleSuggestionSelect}
        />
      )}
    </>
  );
}
