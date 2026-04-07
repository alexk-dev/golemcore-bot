import { type ReactElement, useState } from 'react';
import { FiPlay, FiSave, FiTrash2 } from 'react-icons/fi';
import toast from 'react-hot-toast';
import type { TestModelResponse } from '../../../api/models';
import ConfirmModal from '../../../components/common/ConfirmModal';
import { SaveStateHint } from '../../../components/common/SettingsSaveBar';
import { Button } from '../../../components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '../../../components/ui/card';
import { Badge } from '../../../components/ui/badge';
import { Modal } from '../../../components/ui/bootstrap-overlay';
import { Form } from '../../../lib/react-bootstrap';
import { useTestModel } from '../../../hooks/useModels';
import type { ProviderProfileSummary } from './modelCatalogProviderProfiles';
import { ModelCatalogIdentityFields } from './ModelCatalogIdentityFields';
import { ReasoningLevelsEditor } from './ReasoningLevelsEditor';
import { type ModelDraft, toPersistedModelId } from './modelCatalogTypes';

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

  function handleTestModel(): void {
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
        <CardHeader className="items-start">
          <div className="space-y-2">
            <div className="flex flex-wrap items-center gap-2">
              <CardTitle>{title}</CardTitle>
              <Badge variant={isExisting ? 'secondary' : 'default'}>
                {isExisting ? 'Existing' : 'New'}
              </Badge>
              <Badge variant={draft.supportsVision ? 'info' : 'secondary'}>
                {draft.supportsVision ? 'Vision enabled' : 'Text only'}
              </Badge>
            </div>
            <p className="text-sm text-muted-foreground">
              Model IDs act as stable catalog keys. Existing IDs stay locked to keep updates predictable.
            </p>
          </div>

          <div className="flex flex-wrap gap-2">
            <Button onClick={onSave} disabled={isSaving}>
              <FiSave size={15} />
              {isSaving ? 'Saving...' : isExisting ? 'Save Changes' : 'Create Model'}
            </Button>
            <Button
              variant="secondary"
              onClick={handleTestModel}
              disabled={testModelMutation.isPending || draft.id.trim().length === 0 || draft.provider.trim().length === 0}
            >
              <FiPlay size={15} />
              {testModelMutation.isPending ? 'Testing...' : 'Test Model'}
            </Button>
            {isExisting && (
              <Button
                variant="secondary"
                onClick={() => setIsDeleteConfirmOpen(true)}
                disabled={isDeleting}
              >
                <FiTrash2 size={15} />
                {isDeleting ? 'Deleting...' : 'Delete'}
              </Button>
            )}
          </div>
        </CardHeader>

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
