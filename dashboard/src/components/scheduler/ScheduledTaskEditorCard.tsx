import { type ChangeEvent, type Dispatch, type ReactElement, type SetStateAction, useEffect, useState } from 'react';
import { Button, Card, Form } from '../ui/tailwind-components';
import type {
  CreateScheduledTaskRequest,
  UpdateScheduledTaskRequest,
} from '../../api/scheduledTasks';
import type {
  ScheduledTaskExecutionMode,
  SchedulerScheduledTask,
} from '../../api/scheduler';
import { getExplicitModelTierOptions } from '../../lib/modelTiers';

type ScheduledTaskEditorLayout = 'card' | 'plain';

interface ScheduledTaskEditorCardProps {
  featureEnabled: boolean;
  busy: boolean;
  task: SchedulerScheduledTask | null;
  onCreate: (request: CreateScheduledTaskRequest) => Promise<SchedulerScheduledTask | undefined>;
  onUpdate: (
    scheduledTaskId: string,
    request: UpdateScheduledTaskRequest,
  ) => Promise<void>;
  onCancelEdit: () => void;
  layout?: ScheduledTaskEditorLayout;
}

export interface ScheduledTaskEditorFormState {
  title: string;
  description: string;
  prompt: string;
  executionMode: ScheduledTaskExecutionMode;
  shellCommand: string;
  shellWorkingDirectory: string;
  reflectionModelTier: string;
  reflectionTierPriority: boolean;
}

function createInitialFormState(
  task: SchedulerScheduledTask | null,
): ScheduledTaskEditorFormState {
  if (task == null) {
    return {
      title: '',
      description: '',
      prompt: '',
      executionMode: 'AGENT_PROMPT',
      shellCommand: '',
      shellWorkingDirectory: '',
      reflectionModelTier: '',
      reflectionTierPriority: false,
    };
  }

  return {
    title: task.title,
    description: task.description ?? '',
    prompt: task.prompt ?? '',
    executionMode: task.executionMode,
    shellCommand: task.shellCommand ?? '',
    shellWorkingDirectory: task.shellWorkingDirectory ?? '',
    reflectionModelTier: task.reflectionModelTier ?? '',
    reflectionTierPriority: task.reflectionTierPriority,
  };
}

function normalizeOptionalValue(value: string): string | null {
  const trimmed = value.trim();
  return trimmed.length > 0 ? trimmed : null;
}

export function isScheduledTaskSubmitDisabled(
  featureEnabled: boolean,
  busy: boolean,
  form: ScheduledTaskEditorFormState,
): boolean {
  if (!featureEnabled || busy || form.title.trim().length === 0) {
    return true;
  }
  if (form.executionMode === 'SHELL_COMMAND') {
    return form.shellCommand.trim().length === 0;
  }
  return false;
}

export function buildScheduledTaskRequestPayload(
  form: ScheduledTaskEditorFormState,
): CreateScheduledTaskRequest {
  return {
    title: form.title.trim(),
    description: normalizeOptionalValue(form.description),
    prompt: normalizeOptionalValue(form.prompt),
    executionMode: form.executionMode,
    shellCommand: normalizeOptionalValue(form.shellCommand),
    shellWorkingDirectory: normalizeOptionalValue(form.shellWorkingDirectory),
    reflectionModelTier: normalizeOptionalValue(form.reflectionModelTier),
    reflectionTierPriority: form.reflectionTierPriority,
  };
}

interface ExecutionModeTabsProps {
  disabled: boolean;
  value: ScheduledTaskExecutionMode;
  onChange: (executionMode: ScheduledTaskExecutionMode) => void;
}

function ExecutionModeTabs({
  disabled,
  value,
  onChange,
}: ExecutionModeTabsProps): ReactElement {
  const options: Array<{ key: ScheduledTaskExecutionMode; label: string }> = [
    { key: 'AGENT_PROMPT', label: 'Agent prompt' },
    { key: 'SHELL_COMMAND', label: 'Shell command' },
  ];

  return (
    <Form.Group className="mb-3">
      <Form.Label>Execution mode</Form.Label>
      <div
        className="grid grid-cols-2 gap-1 rounded-xl border border-slate-300/80 bg-slate-100/90 p-1 dark:border-slate-700 dark:bg-slate-900/70"
        role="tablist"
        aria-label="Scheduled task execution mode"
      >
        {options.map((option) => {
          const selected = value === option.key;
          return (
            <button
              key={option.key}
              type="button"
              role="tab"
              aria-selected={selected}
              className={
                selected
                  ? 'rounded-lg border border-primary/25 bg-primary px-3 py-2 text-sm font-semibold text-primary-foreground shadow-sm transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary/40'
                  : 'rounded-lg border border-transparent px-3 py-2 text-sm font-medium text-slate-700 transition-colors hover:bg-white hover:text-slate-950 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary/30 dark:text-slate-300 dark:hover:bg-slate-800 dark:hover:text-slate-50'
              }
              disabled={disabled}
              onClick={() => onChange(option.key)}
            >
              {option.label}
            </button>
          );
        })}
      </div>
    </Form.Group>
  );
}

interface ScheduledTaskEditorContentProps {
  featureEnabled: boolean;
  busy: boolean;
  task: SchedulerScheduledTask | null;
  form: ScheduledTaskEditorFormState;
  onFieldChange: (
    field: keyof ScheduledTaskEditorFormState,
  ) => (
    event: ChangeEvent<HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement>,
  ) => void;
  onExecutionModeChange: (executionMode: ScheduledTaskExecutionMode) => void;
  onReflectionTierPriorityChange: (checked: boolean) => void;
  onSubmit: () => void;
  onCancelEdit: () => void;
  showActions?: boolean;
  submitLabel?: string;
}

export function ScheduledTaskEditorContent({
  featureEnabled,
  busy,
  task,
  form,
  onFieldChange,
  onExecutionModeChange,
  onReflectionTierPriorityChange,
  onSubmit,
  onCancelEdit,
  showActions = true,
  submitLabel,
}: ScheduledTaskEditorContentProps): ReactElement {
  const isEditing = task != null;
  const submitDisabled = isScheduledTaskSubmitDisabled(featureEnabled, busy, form);
  const showsShellFields = form.executionMode === 'SHELL_COMMAND';

  return (
    <>
      {isEditing && (
        <div className="small text-body-secondary mb-3">
          Scheduled task <code>{task.id}</code>
        </div>
      )}

      <Form.Group className="mb-3">
        <Form.Label>Title</Form.Label>
        <Form.Control
          size="sm"
          value={form.title}
          onChange={onFieldChange('title')}
          disabled={!featureEnabled}
          placeholder="Nightly repository sweep"
        />
      </Form.Group>

      <Form.Group className="mb-3">
        <Form.Label>Details</Form.Label>
        <Form.Control
          as="textarea"
          rows={3}
          value={form.description}
          onChange={onFieldChange('description')}
          disabled={!featureEnabled}
          placeholder="Optional notes for operators."
        />
      </Form.Group>

      <ExecutionModeTabs
        disabled={!featureEnabled}
        value={form.executionMode}
        onChange={onExecutionModeChange}
      />

      {!showsShellFields && (
        <>
          <Form.Group className="mb-3">
            <Form.Label>Prompt</Form.Label>
            <Form.Control
              as="textarea"
              rows={6}
              value={form.prompt}
              onChange={onFieldChange('prompt')}
              disabled={!featureEnabled}
              placeholder="Leave empty to fall back to the title during execution."
            />
            <Form.Text className="text-body-secondary">
              Runs the agent with this prompt. If empty, the title is used.
            </Form.Text>
          </Form.Group>

          <Form.Group className="mb-3">
            <Form.Label>Reflection tier</Form.Label>
            <Form.Select
              size="sm"
              value={form.reflectionModelTier}
              onChange={onFieldChange('reflectionModelTier')}
              disabled={!featureEnabled}
            >
              <option value="">Use default reflection model</option>
              {getExplicitModelTierOptions().map((option) => (
                <option key={option.value} value={option.value}>
                  {option.label}
                </option>
              ))}
            </Form.Select>
          </Form.Group>

          <Form.Check
            type="switch"
            className="mb-3"
            label="Reflection tier has priority"
            checked={form.reflectionTierPriority}
            disabled={!featureEnabled || form.reflectionModelTier.length === 0}
            onChange={(event) => onReflectionTierPriorityChange(event.target.checked)}
          />
        </>
      )}

      {showsShellFields && (
        <>
          <Form.Group className="mb-3">
            <Form.Label>Command</Form.Label>
            <Form.Control
              as="textarea"
              rows={5}
              value={form.shellCommand}
              onChange={onFieldChange('shellCommand')}
              disabled={!featureEnabled}
              placeholder="npm run nightly-sync"
            />
            <Form.Text className="text-body-secondary">
              Executed through the bot shell tool. Required for shell mode.
            </Form.Text>
          </Form.Group>

          <Form.Group className="mb-3">
            <Form.Label>Working directory</Form.Label>
            <Form.Control
              size="sm"
              value={form.shellWorkingDirectory}
              onChange={onFieldChange('shellWorkingDirectory')}
              disabled={!featureEnabled}
              placeholder="services/api"
            />
            <Form.Text className="text-body-secondary">
              Optional. Relative to the shell workspace; empty uses the shell workspace root.
            </Form.Text>
          </Form.Group>
        </>
      )}

      {showActions && (
        <div className="d-flex gap-2 justify-content-end">
          {isEditing && (
            <Button
              type="button"
              size="sm"
              variant="secondary"
              onClick={onCancelEdit}
            >
              Cancel
            </Button>
          )}
          <Button
            type="button"
            size="sm"
            variant="primary"
            disabled={submitDisabled}
            onClick={onSubmit}
          >
            {busy ? 'Saving...' : submitLabel ?? (isEditing ? 'Save task' : 'Create task')}
          </Button>
        </div>
      )}
    </>
  );
}

interface ScheduledTaskEditorState {
  form: ScheduledTaskEditorFormState;
  isEditing: boolean;
  setForm: Dispatch<SetStateAction<ScheduledTaskEditorFormState>>;
  resetForm: () => void;
  handleFieldChange: (
    field: keyof ScheduledTaskEditorFormState,
  ) => (
    event: ChangeEvent<HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement>,
  ) => void;
  handleExecutionModeChange: (executionMode: ScheduledTaskExecutionMode) => void;
  setReflectionTierPriority: (checked: boolean) => void;
  buildRequestPayload: () => CreateScheduledTaskRequest;
}

export function useScheduledTaskEditorState(
  task: SchedulerScheduledTask | null,
): ScheduledTaskEditorState {
  const [form, setForm] = useState<ScheduledTaskEditorFormState>(
    () => createInitialFormState(task),
  );
  const isEditing = task != null;

  useEffect(() => {
    setForm(createInitialFormState(task));
  }, [task]);

  const handleFieldChange = (
    field: keyof ScheduledTaskEditorFormState,
  ) => (
    event: ChangeEvent<HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement>,
  ): void => {
    const value = event.target.value;
    setForm((current) => ({ ...current, [field]: value }));
  };

  const handleExecutionModeChange = (executionMode: ScheduledTaskExecutionMode): void => {
    setForm((current) => ({ ...current, executionMode }));
  };

  return {
    form,
    isEditing,
    setForm,
    resetForm: () => setForm(createInitialFormState(task)),
    handleFieldChange,
    handleExecutionModeChange,
    setReflectionTierPriority: (checked) => setForm((current) => ({
      ...current,
      reflectionTierPriority: checked,
    })),
    buildRequestPayload: () => buildScheduledTaskRequestPayload(form),
  };
}

function renderContent(
  layout: ScheduledTaskEditorLayout,
  title: string,
  content: ReactElement,
): ReactElement {
  if (layout === 'plain') {
    return content;
  }

  return (
    <Card className="h-100">
      <Card.Header className="fw-semibold">{title}</Card.Header>
      <Card.Body>{content}</Card.Body>
    </Card>
  );
}

export function ScheduledTaskEditorCard({
  featureEnabled,
  busy,
  task,
  onCreate,
  onUpdate,
  onCancelEdit,
  layout = 'card',
}: ScheduledTaskEditorCardProps): ReactElement {
  const {
    form,
    isEditing,
    handleFieldChange,
    handleExecutionModeChange,
    setReflectionTierPriority,
    buildRequestPayload,
    setForm,
  } = useScheduledTaskEditorState(task);

  const handleSubmit = async (): Promise<void> => {
    if (isScheduledTaskSubmitDisabled(featureEnabled, busy, form)) {
      return;
    }

    const request = buildRequestPayload();
    if (task == null) {
      await onCreate(request);
      setForm(createInitialFormState(null));
      return;
    }

    await onUpdate(task.id, request);
  };

  const content = (
    <ScheduledTaskEditorContent
      featureEnabled={featureEnabled}
      busy={busy}
      task={task}
      form={form}
      onFieldChange={handleFieldChange}
      onExecutionModeChange={handleExecutionModeChange}
      onReflectionTierPriorityChange={setReflectionTierPriority}
      onSubmit={() => {
        void handleSubmit();
      }}
      onCancelEdit={onCancelEdit}
    />
  );

  return renderContent(
    layout,
    isEditing ? 'Edit scheduled task' : 'Create scheduled task',
    content,
  );
}
