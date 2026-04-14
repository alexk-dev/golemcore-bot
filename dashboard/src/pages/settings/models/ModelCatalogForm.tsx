import { type ReactElement, useState } from 'react';
import toast from 'react-hot-toast';
import type { TestModelResponse } from '../../../api/models';
import ConfirmModal from '../../../components/common/ConfirmModal';
import { SaveStateHint } from '../../../components/common/SettingsSaveBar';
import { Button } from '../../../components/ui/button';
import { Card, CardContent } from '../../../components/ui/card';
import { Modal } from '../../../components/ui/overlay';
import { Form } from '../../../components/ui/tailwind-components';
import { useTestModel } from '../../../hooks/useModels';
import type { ProviderProfileSummary } from './modelCatalogProviderProfiles';
import { ModelCatalogFormHeader } from './ModelCatalogFormHeader';
import { ModelCatalogIdentityFields } from './ModelCatalogIdentityFields';
import { ReasoningLevelsEditor } from './ReasoningLevelsEditor';
import { canTestModelDraft, type ModelDraft, toPersistedModelId } from './modelCatalogTypes';

interface ModelCatalogFormProps {
  draft: ModelDraft;
  isDirty: boolean;
  isExisting: boolean;
  isSaving: boolean;
  isDeleting: boolean;
  providerProfiles: ProviderProfileSummary[];
  onDraftChange: (draft: ModelDraft) => void;
  onSave: () => void;
  onDelete: () => void;
}

function buildTitle(draft: ModelDraft, isExisting: boolean): string {
  if (!isExisting && draft.id.trim().length === 0) {
    return 'New Model';
  }
  if (draft.displayName.trim().length > 0) {
    return draft.displayName.trim();
  }
  return isExisting ? draft.id : 'Draft Model';
}

function buildReasoningState(nextChecked: boolean, draft: ModelDraft): ModelDraft {
  if (!nextChecked) {
    return {
      ...draft,
      reasoningEnabled: false,
      reasoningDefault: '',
      reasoningLevels: [],
    };
  }

  if (draft.reasoningLevels.length > 0) {
    return { ...draft, reasoningEnabled: true };
  }

  return {
    ...draft,
    reasoningEnabled: true,
    reasoningDefault: 'medium',
    reasoningLevels: [{ level: 'medium', maxInputTokens: draft.maxInputTokens }],
  };
}

function ensureReasoningDefault(draft: ModelDraft, nextLevels: ModelDraft['reasoningLevels']): string {
  const trimmedDefault = draft.reasoningDefault.trim();
  const availableLevels = nextLevels.map((level) => level.level.trim()).filter((level) => level.length > 0);
  if (trimmedDefault.length > 0 && availableLevels.includes(trimmedDefault)) {
    return trimmedDefault;
  }
  return availableLevels[0] ?? '';
}

export function ModelCatalogForm({
  draft,
  isDirty,
  isExisting,
  isSaving,
  isDeleting,
  providerProfiles,
  onDraftChange,
  onSave,
  onDelete,
}: ModelCatalogFormProps): ReactElement {
  const [isDeleteConfirmOpen, setIsDeleteConfirmOpen] = useState(false);
  const [testResult, setTestResult] = useState<TestModelResponse | null>(null);
  const testModelMutation = useTestModel();
  const title = buildTitle(draft, isExisting);
  const canTestModel = canTestModelDraft(draft, isExisting, isDirty);

  function handleTestModel(): void {
    if (!canTestModel) {
      toast.error(isExisting ? 'Save changes before testing this model.' : 'Create the model before testing it.');
      return;
    }

    const model = toPersistedModelId(draft);
    if (model.length === 0) {
      toast.error('Enter a model ID and provider before testing.');
      return;
    }
    setTestResult(null);
    testModelMutation.mutate(model, {
      onSuccess: (result) => setTestResult(result),
      onError: () => toast.error('Failed to send test request.'),
    });
  }

  return (
    <>
      <Card className="h-full">
        <ModelCatalogFormHeader
          title={title}
          isExisting={isExisting}
          isSaving={isSaving}
          isDeleting={isDeleting}
          isTesting={testModelMutation.isPending}
          canTestModel={canTestModel}
          supportsVision={draft.supportsVision}
          onSave={onSave}
          onTest={handleTestModel}
          onDeleteClick={() => setIsDeleteConfirmOpen(true)}
        />

        <CardContent className="space-y-6">
          <ModelCatalogIdentityFields
            draft={draft}
            isExisting={isExisting}
            providerProfiles={providerProfiles}
            onDraftChange={onDraftChange}
          />

          <div className="grid gap-4 lg:grid-cols-2">
            <Form.Check
              type="switch"
              checked={draft.supportsVision}
              onChange={(event) => onDraftChange({ ...draft, supportsVision: event.target.checked })}
              label={
                <div>
                  <div className="font-medium text-foreground">Supports Vision</div>
                  <div className="text-sm text-muted-foreground">
                    Allow the model to receive image attachments and multimodal prompts.
                  </div>
                </div>
              }
            />

            <Form.Check
              type="switch"
              checked={draft.supportsTemperature}
              onChange={(event) => onDraftChange({ ...draft, supportsTemperature: event.target.checked })}
              label={
                <div>
                  <div className="font-medium text-foreground">Supports Temperature</div>
                  <div className="text-sm text-muted-foreground">
                    Disable this for reasoning-first models that ignore or reject temperature.
                  </div>
                </div>
              }
            />
          </div>

          <Form.Check
            type="switch"
            checked={draft.reasoningEnabled}
            onChange={(event) => onDraftChange(buildReasoningState(event.target.checked, draft))}
            label={
              <div>
                <div className="font-medium text-foreground">Reasoning Configuration</div>
                <div className="text-sm text-muted-foreground">
                  Turn this on for models with named reasoning levels like `low`, `medium`, or `high`.
                </div>
              </div>
            }
          />

          {draft.reasoningEnabled && (
            <ReasoningLevelsEditor
              defaultLevel={draft.reasoningDefault}
              levels={draft.reasoningLevels}
              onDefaultLevelChange={(value) => onDraftChange({ ...draft, reasoningDefault: value })}
              onAddLevel={() => {
                const nextLevels = [...draft.reasoningLevels, { level: '', maxInputTokens: draft.maxInputTokens }];
                onDraftChange({
                  ...draft,
                  reasoningLevels: nextLevels,
                  reasoningDefault: ensureReasoningDefault(draft, nextLevels),
                });
              }}
              onRemoveLevel={(index) => {
                const nextLevels = draft.reasoningLevels.filter((_, currentIndex) => currentIndex !== index);
                onDraftChange({
                  ...draft,
                  reasoningLevels: nextLevels,
                  reasoningDefault: ensureReasoningDefault(draft, nextLevels),
                });
              }}
              onUpdateLevel={(index, field, value) => {
                const nextLevels = draft.reasoningLevels.map((level, currentIndex) => (
                  currentIndex === index
                    ? { ...level, [field]: value }
                    : level
                ));
                onDraftChange({
                  ...draft,
                  reasoningLevels: nextLevels,
                  reasoningDefault: ensureReasoningDefault(draft, nextLevels),
                });
              }}
            />
          )}

          <div className="rounded-2xl border border-border/80 bg-muted/20 px-4 py-3">
            <SaveStateHint isDirty={isDirty} />
          </div>

        </CardContent>
      </Card>

      <Modal show={testResult != null} onHide={() => setTestResult(null)} centered>
        <Modal.Header closeButton>
          <Modal.Title>{testResult?.success ? 'Model Response' : 'Test Failed'}</Modal.Title>
        </Modal.Header>
        <Modal.Body>
          <div className={`rounded-2xl border px-4 py-3 text-sm ${
            testResult?.success
              ? 'border-green-500/30 bg-green-500/10 text-green-700 dark:text-green-300'
              : 'border-destructive/30 bg-destructive/10 text-destructive dark:text-red-300'
          }`}>
            <div className="whitespace-pre-wrap break-words">
              {testResult?.success ? testResult.reply : testResult?.error}
            </div>
          </div>
        </Modal.Body>
        <Modal.Footer>
          <Button variant="secondary" size="sm" onClick={() => setTestResult(null)}>
            Close
          </Button>
        </Modal.Footer>
      </Modal>

      <ConfirmModal
        show={isDeleteConfirmOpen}
        title="Delete model"
        message={`Delete "${draft.id}" from the catalog? This does not remove provider credentials, only the model definition.`}
        confirmLabel={isDeleting ? 'Deleting...' : 'Delete model'}
        isProcessing={isDeleting}
        onConfirm={() => {
          setIsDeleteConfirmOpen(false);
          onDelete();
        }}
        onCancel={() => setIsDeleteConfirmOpen(false)}
      />
    </>
  );
}
