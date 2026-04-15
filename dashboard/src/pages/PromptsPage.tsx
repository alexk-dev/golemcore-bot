import type { ReactElement } from 'react';
import { PromptDeleteDialog, PromptUnsavedChangesDialog } from '../components/prompts/PromptDialogs';
import { PromptEditor } from '../components/prompts/PromptEditor';
import { PromptsSidebar } from '../components/prompts/PromptsSidebar';
import { Alert } from '../components/ui/alert';
import { Card, CardContent } from '../components/ui/card';
import { Skeleton } from '../components/ui/skeleton';
import { usePromptsWorkspace } from '../hooks/usePromptsWorkspace';
import { extractErrorMessage } from '../utils/extractErrorMessage';

function PromptsPageSkeleton(): ReactElement {
  return (
    <div className="grid min-w-0 gap-6 xl:grid-cols-[22rem_minmax(0,1fr)]">
      <Card>
        <CardContent className="space-y-3 p-5">
          <Skeleton className="h-8 w-32" />
          <Skeleton className="h-10 w-full" />
          <Skeleton className="h-28 w-full rounded-3xl" />
          <Skeleton className="h-28 w-full rounded-3xl" />
        </CardContent>
      </Card>
      <Card>
        <CardContent className="space-y-4 p-5">
          <Skeleton className="h-8 w-44" />
          <Skeleton className="h-12 w-full" />
          <Skeleton className="h-12 w-full" />
          <Skeleton className="h-[28rem] w-full rounded-3xl" />
        </CardContent>
      </Card>
    </div>
  );
}

function EmptyPromptState(): ReactElement {
  return (
    <Card>
      <CardContent className="flex min-h-[28rem] items-center justify-center p-6">
        <div className="max-w-md text-center">
          <h2 className="text-lg font-semibold text-foreground">No prompt selected</h2>
          <p className="mt-3 text-sm leading-6 text-muted-foreground">
            Choose a section from the catalog or create a new one to start editing.
          </p>
        </div>
      </CardContent>
    </Card>
  );
}

export default function PromptsPage(): ReactElement {
  const workspace = usePromptsWorkspace();

  if (workspace.isLoading) {
    return <PromptsPageSkeleton />;
  }

  return (
    <>
      <div className="space-y-6">
        <div className="flex flex-col gap-4 lg:flex-row lg:items-end lg:justify-between">
          <div>
            <h1 className="font-display text-3xl font-semibold tracking-tight text-foreground">Prompts</h1>
            <p className="mt-2 max-w-3xl text-sm leading-6 text-muted-foreground">
              Prompt sections are injected into the system prompt by ascending priority. Lower values execute earlier.
            </p>
          </div>
          <Alert variant="secondary" className="max-w-xl">
            `identity` and `rules` remain editable but protected. Their delete action is intentionally disabled.
          </Alert>
        </div>

        {workspace.isError && (
          <Alert variant="danger">
            Failed to load prompts: {extractErrorMessage(workspace.error)}
          </Alert>
        )}

        <div className="grid min-w-0 gap-6 xl:grid-cols-[22rem_minmax(0,1fr)]">
          <PromptsSidebar
            sections={workspace.sections}
            selectedName={workspace.selectedName}
            isCreating={workspace.isCreating}
            isReordering={workspace.isReordering}
            hasUnsavedChanges={workspace.isDirty}
            createResetToken={workspace.createResetToken}
            onCreate={workspace.requestCreate}
            onReorder={workspace.requestReorder}
            onSelect={workspace.requestSelect}
          />

          {workspace.draft != null ? (
            <PromptEditor
              draft={workspace.draft}
              preview={workspace.preview}
              previewError={workspace.previewError}
              isDirty={workspace.isDirty}
              isSaving={workspace.isSaving}
              isPreviewing={workspace.isPreviewing}
              isDeleting={workspace.isDeleting}
              priorityConflictName={workspace.priorityConflictName}
              onChange={workspace.updateDraft}
              onReset={workspace.resetDraft}
              onSave={() => {
                void workspace.saveDraft();
              }}
              onPreview={() => {
                void workspace.previewDraft();
              }}
              onDelete={workspace.requestDelete}
            />
          ) : (
            <EmptyPromptState />
          )}
        </div>
      </div>

      <PromptDeleteDialog
        show={workspace.isDeleteDialogOpen}
        promptName={workspace.draft?.name ?? ''}
        isProcessing={workspace.isDeleting}
        onConfirm={() => {
          void workspace.confirmDelete();
        }}
        onCancel={workspace.closeDeleteDialog}
      />

      <PromptUnsavedChangesDialog
        show={workspace.isUnsavedDialogOpen}
        promptName={workspace.draft?.name ?? ''}
        isSaving={workspace.isSaving}
        onSaveAndContinue={() => {
          void workspace.saveAndContinue();
        }}
        onDiscardAndContinue={workspace.discardAndContinue}
        onCancel={workspace.closeUnsavedDialog}
      />
    </>
  );
}
