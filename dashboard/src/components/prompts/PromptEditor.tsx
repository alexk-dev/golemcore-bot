import type { ReactElement } from 'react';
import { FiEye, FiLock, FiSave, FiTrash2 } from 'react-icons/fi';
import { Alert } from '../ui/alert';
import { Badge } from '../ui/badge';
import { Button } from '../ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '../ui/card';
import { Input, Textarea } from '../ui/field';
import { parsePromptOrder, type PromptDraft } from './promptFormUtils';

export interface PromptEditorProps {
  draft: PromptDraft;
  preview: string;
  previewError: string | null;
  isDirty: boolean;
  isSaving: boolean;
  isPreviewing: boolean;
  isDeleting: boolean;
  priorityConflictName: string | null;
  onChange: (nextDraft: PromptDraft) => void;
  onReset: () => void;
  onSave: () => void;
  onPreview: () => void;
  onDelete: () => void;
}

interface PromptPreviewPanelProps {
  preview: string;
  previewError: string | null;
  isPreviewing: boolean;
}

function PromptPreviewPanel({
  preview,
  previewError,
  isPreviewing,
}: PromptPreviewPanelProps): ReactElement {
  return (
    <div className="space-y-3 rounded-[1.5rem] border border-border/80 bg-slate-950/95 p-4 text-slate-100 xl:sticky xl:top-6">
      <div className="flex items-center justify-between gap-3">
        <div>
          <div className="text-sm font-semibold text-slate-100">Live Preview</div>
          <p className="mt-1 text-sm leading-6 text-slate-400">
            Updates automatically from the current draft.
          </p>
        </div>
        {isPreviewing && (
          <Badge variant="info" className="border-cyan-400/20 bg-cyan-400/10 text-cyan-200">
            updating
          </Badge>
        )}
      </div>

      {previewError != null && (
        <Alert variant="danger" className="border-red-400/20 bg-red-500/10 text-red-100">
          Preview failed: {previewError}
        </Alert>
      )}

      {preview.length > 0 ? (
        <pre className="min-h-[24rem] overflow-x-auto rounded-2xl border border-slate-800 bg-slate-950/80 p-4 font-mono text-sm leading-7 text-slate-100">
          {preview}
        </pre>
      ) : (
        <div className="flex min-h-[24rem] items-center justify-center rounded-2xl border border-dashed border-slate-800 bg-slate-950/70 px-6 text-center text-sm leading-6 text-slate-400">
          {isPreviewing ? 'Rendering preview…' : 'Preview appears here as soon as the draft is rendered.'}
        </div>
      )}
    </div>
  );
}

interface PromptEditorAlertsProps {
  draft: PromptDraft;
  priorityConflictName: string | null;
}

function PromptEditorAlerts({ draft, priorityConflictName }: PromptEditorAlertsProps): ReactElement {
  const isProtectedAndDisabled = !draft.deletable && !draft.enabled;

  return (
    <>
      <Alert variant="info">
        Prompt sections are assembled by ascending priority. `identity` and `rules` always stay in the catalog, but you
        can still edit their content and enabled state here.
      </Alert>

      {priorityConflictName != null && (
        <Alert variant="warning">
          Priority <strong className="text-foreground">{draft.order}</strong> is already used by{' '}
          <strong className="text-foreground">{priorityConflictName}</strong>. Equal priorities are still allowed, but
          their ordering becomes less obvious for operators.
        </Alert>
      )}

      {isProtectedAndDisabled && (
        <Alert variant="warning">
          This protected prompt is disabled. That can materially change the baseline system behavior.
        </Alert>
      )}
    </>
  );
}

interface PromptEditorActionBarProps {
  deleteHelpText: string;
  isDirty: boolean;
  isSaving: boolean;
  isPreviewing: boolean;
  isDeleting: boolean;
  isDeletable: boolean;
  onReset: () => void;
  onPreview: () => void;
  onSave: () => void;
  onDelete: () => void;
}

function PromptEditorActionBar({
  deleteHelpText,
  isDirty,
  isSaving,
  isPreviewing,
  isDeleting,
  isDeletable,
  onReset,
  onPreview,
  onSave,
  onDelete,
}: PromptEditorActionBarProps): ReactElement {
  return (
    <div className="flex flex-wrap items-center justify-between gap-3">
      <div className="space-y-1">
        <p className="text-sm leading-6 text-muted-foreground">{deleteHelpText}</p>
        {isDirty && <p className="text-sm font-medium text-amber-700 dark:text-amber-200">Unsaved changes</p>}
      </div>
      <div className="flex flex-wrap gap-2">
        <Button
          variant="ghost"
          onClick={onReset}
          disabled={!isDirty || isSaving}
        >
          Reset
        </Button>
        <Button
          variant="secondary"
          onClick={onPreview}
          disabled={isPreviewing || isDeleting}
        >
          <FiEye size={14} />
          {isPreviewing ? 'Refreshing...' : 'Refresh'}
        </Button>
        <Button
          onClick={onSave}
          disabled={!isDirty || isSaving || isDeleting}
        >
          <FiSave size={14} />
          {isSaving ? 'Saving...' : 'Save'}
        </Button>
        <Button
          variant="destructive"
          onClick={onDelete}
          disabled={!isDeletable || isDeleting}
          title={deleteHelpText}
        >
          <FiTrash2 size={14} />
          {isDeleting ? 'Deleting...' : 'Delete'}
        </Button>
      </div>
    </div>
  );
}

function updateDraftField<TKey extends keyof PromptDraft>(
  draft: PromptDraft,
  field: TKey,
  value: PromptDraft[TKey]
): PromptDraft {
  return { ...draft, [field]: value };
}

export function PromptEditor({
  draft,
  preview,
  previewError,
  isDirty,
  isSaving,
  isPreviewing,
  isDeleting,
  priorityConflictName,
  onChange,
  onReset,
  onSave,
  onPreview,
  onDelete,
}: PromptEditorProps): ReactElement {
  const deleteHelpText = draft.deletable
    ? 'Delete this custom prompt section.'
    : 'Built-in identity and rules prompts are protected and cannot be deleted.';

  return (
    <div className="space-y-4">
      <Card className="overflow-hidden">
        <CardHeader>
          <div className="space-y-2">
            <div className="flex flex-wrap items-center gap-2">
              <CardTitle>{draft.name}</CardTitle>
              <Badge variant={draft.enabled ? 'success' : 'warning'}>
                {draft.enabled ? 'enabled' : 'disabled'}
              </Badge>
              <Badge variant="secondary">priority {draft.order}</Badge>
              {!draft.deletable && (
                <Badge variant="secondary" className="gap-1">
                  <FiLock size={11} />
                  protected
                </Badge>
              )}
            </div>
            <CardDescription>
              Lower priority numbers are injected earlier into the system prompt.
            </CardDescription>
          </div>
        </CardHeader>
        <CardContent className="space-y-5">
          <PromptEditorAlerts draft={draft} priorityConflictName={priorityConflictName} />

          <div className="grid gap-4 lg:grid-cols-[minmax(0,1fr)_11rem_12rem]">
            <div className="space-y-2">
              <label className="text-xs font-semibold uppercase tracking-[0.16em] text-muted-foreground">
                Description
              </label>
              <Input
                value={draft.description}
                onChange={(event) => onChange(updateDraftField(draft, 'description', event.target.value))}
                placeholder="Short summary for operators"
              />
            </div>

            <div className="space-y-2">
              <label className="text-xs font-semibold uppercase tracking-[0.16em] text-muted-foreground">
                Priority
              </label>
              <Input
                type="number"
                value={draft.order}
                onChange={(event) =>
                  onChange(updateDraftField(draft, 'order', parsePromptOrder(event.target.value, draft.order)))
                }
              />
            </div>

            <label className="flex items-center justify-between rounded-2xl border border-border/80 bg-muted/20 px-4 py-3">
              <div>
                <div className="text-sm font-semibold text-foreground">Enabled</div>
                <p className="mt-1 text-sm leading-6 text-muted-foreground">Inactive prompts stay stored but are not injected.</p>
              </div>
              <input
                type="checkbox"
                checked={draft.enabled}
                onChange={(event) => onChange(updateDraftField(draft, 'enabled', event.target.checked))}
                className="h-4 w-4 rounded border-border text-primary focus:ring-primary"
              />
            </label>
          </div>

          <div className="grid gap-4 xl:grid-cols-[minmax(0,1.05fr)_minmax(20rem,0.95fr)]">
            <div className="space-y-2">
              <label className="text-xs font-semibold uppercase tracking-[0.16em] text-muted-foreground">
                Content
              </label>
              <Textarea
                rows={18}
                value={draft.content}
                onChange={(event) => onChange(updateDraftField(draft, 'content', event.target.value))}
                placeholder="Write the prompt body here..."
                className="min-h-[24rem] bg-slate-950/90 font-mono text-[0.9rem] leading-7 text-slate-100 placeholder:text-slate-400"
              />
            </div>

            <PromptPreviewPanel
              preview={preview}
              previewError={previewError}
              isPreviewing={isPreviewing}
            />
          </div>

          <PromptEditorActionBar
            deleteHelpText={deleteHelpText}
            isDirty={isDirty}
            isSaving={isSaving}
            isPreviewing={isPreviewing}
            isDeleting={isDeleting}
            isDeletable={draft.deletable}
            onReset={onReset}
            onPreview={onPreview}
            onSave={onSave}
            onDelete={onDelete}
          />
        </CardContent>
      </Card>
    </div>
  );
}
