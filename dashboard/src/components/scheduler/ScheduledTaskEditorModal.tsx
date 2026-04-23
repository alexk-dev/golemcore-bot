import { type ReactElement, useMemo, useState } from 'react';
import { Button, Form, Modal } from '../ui/tailwind-components';
import type {
  CreateScheduledTaskRequest,
  UpdateScheduledTaskRequest,
} from '../../api/scheduledTasks';
import type {
  CreateScheduleRequest,
  SchedulerScheduledTask,
} from '../../api/scheduler';
import { useSchedulerForm } from '../../hooks/useSchedulerForm';
import { SchedulerCreateCard } from './SchedulerCreateCard';
import type { ReportChannelOption } from './SchedulerCreateCardReportChannel';
import {
  ScheduledTaskEditorCard,
  ScheduledTaskEditorContent,
  isScheduledTaskSubmitDisabled,
  useScheduledTaskEditorState,
} from './ScheduledTaskEditorCard';

type CreateModalTab = 'task' | 'schedule';

const PENDING_TARGET_ID = '__pending_scheduled_task__';

interface ScheduledTaskEditorModalProps {
  show: boolean;
  featureEnabled: boolean;
  busy: boolean;
  scheduleBusy: boolean;
  task: SchedulerScheduledTask | null;
  reportChannelOptions: ReportChannelOption[];
  onHide: () => void;
  onCreate: (request: CreateScheduledTaskRequest) => Promise<SchedulerScheduledTask>;
  onCreateSchedule: (request: CreateScheduleRequest) => Promise<void> | void;
  onUpdate: (
    scheduledTaskId: string,
    request: UpdateScheduledTaskRequest,
  ) => Promise<void>;
}

function buildPendingTask(form: ReturnType<typeof useScheduledTaskEditorState>['form']): SchedulerScheduledTask {
  return {
    id: PENDING_TARGET_ID,
    title: form.title.trim().length > 0 ? form.title.trim() : 'New scheduled task',
    description: form.description.trim().length > 0 ? form.description.trim() : null,
    prompt: form.prompt.trim().length > 0 ? form.prompt.trim() : null,
    executionMode: form.executionMode,
    shellCommand: form.shellCommand.trim().length > 0 ? form.shellCommand.trim() : null,
    shellWorkingDirectory: form.shellWorkingDirectory.trim().length > 0
      ? form.shellWorkingDirectory.trim()
      : null,
    reflectionModelTier: form.reflectionModelTier.trim().length > 0 ? form.reflectionModelTier.trim() : null,
    reflectionTierPriority: form.reflectionTierPriority,
    legacySourceType: null,
    legacySourceId: null,
  };
}

function CreateModalTabs({
  activeTab,
  onChange,
}: {
  activeTab: CreateModalTab;
  onChange: (tab: CreateModalTab) => void;
}): ReactElement {
  const tabs: Array<{ key: CreateModalTab; label: string }> = [
    { key: 'task', label: 'Task' },
    { key: 'schedule', label: 'Initial schedule' },
  ];

  return (
    <div
      className="mb-4 grid grid-cols-2 gap-1 rounded-xl border border-slate-300/80 bg-slate-100/90 p-1 dark:border-slate-700 dark:bg-slate-900/70"
      role="tablist"
      aria-label="Scheduled task creation tabs"
    >
      {tabs.map((tab) => {
        const selected = activeTab === tab.key;
        return (
          <button
            key={tab.key}
            type="button"
            role="tab"
            aria-selected={selected}
            className={
              selected
                ? 'rounded-lg border border-primary/25 bg-primary px-3 py-2 text-sm font-semibold text-primary-foreground shadow-sm transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary/40'
                : 'rounded-lg border border-transparent px-3 py-2 text-sm font-medium text-slate-700 transition-colors hover:bg-white hover:text-slate-950 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary/30 dark:text-slate-300 dark:hover:bg-slate-800 dark:hover:text-slate-50'
            }
            onClick={() => onChange(tab.key)}
          >
            {tab.label}
          </button>
        );
      })}
    </div>
  );
}

export function ScheduledTaskEditorModal({
  show,
  featureEnabled,
  busy,
  scheduleBusy,
  task,
  reportChannelOptions,
  onHide,
  onCreate,
  onCreateSchedule,
  onUpdate,
}: ScheduledTaskEditorModalProps): ReactElement {
  const taskEditor = useScheduledTaskEditorState(task);
  const [activeTab, setActiveTab] = useState<CreateModalTab>('task');
  const [createInitialSchedule, setCreateInitialSchedule] = useState(false);
  const pendingTask = useMemo(
    () => buildPendingTask(taskEditor.form),
    [taskEditor.form],
  );
  const scheduleForm = useSchedulerForm([pendingTask]);
  const isCreateMode = task == null;
  const taskSubmitDisabled = isScheduledTaskSubmitDisabled(featureEnabled, busy, taskEditor.form);
  const scheduleSubmitDisabled = createInitialSchedule && !scheduleForm.isFormValid;

  const handleHide = (): void => {
    setActiveTab('task');
    setCreateInitialSchedule(false);
    scheduleForm.reset();
    onHide();
  };

  const handleCreate = async (): Promise<void> => {
    if (taskSubmitDisabled || scheduleSubmitDisabled) {
      return;
    }

    const createdTask = await onCreate(taskEditor.buildRequestPayload());
    if (createInitialSchedule) {
      const scheduleRequest = scheduleForm.buildCreateRequest();
      if (scheduleRequest != null) {
        await onCreateSchedule({
          ...scheduleRequest,
          targetId: createdTask.id,
        });
      }
    }
    handleHide();
  };

  if (!isCreateMode) {
    return (
      <Modal show={show} onHide={handleHide} size="lg" centered>
        <Modal.Header closeButton>
          <Modal.Title>Edit scheduled task</Modal.Title>
        </Modal.Header>
        <Modal.Body>
          <ScheduledTaskEditorCard
            layout="plain"
            featureEnabled={featureEnabled}
            busy={busy}
            task={task}
            onCreate={onCreate}
            onUpdate={onUpdate}
            onCancelEdit={handleHide}
          />
        </Modal.Body>
      </Modal>
    );
  }

  return (
    <Modal show={show} onHide={handleHide} size="lg" centered>
      <Modal.Header closeButton>
        <Modal.Title>Create scheduled task</Modal.Title>
      </Modal.Header>
      <Modal.Body>
        <CreateModalTabs activeTab={activeTab} onChange={setActiveTab} />

        {activeTab === 'task' ? (
          <ScheduledTaskEditorContent
            featureEnabled={featureEnabled}
            busy={busy}
            task={null}
            form={taskEditor.form}
            onFieldChange={taskEditor.handleFieldChange}
            onExecutionModeChange={taskEditor.handleExecutionModeChange}
            onReflectionTierPriorityChange={taskEditor.setReflectionTierPriority}
            onSubmit={() => { void handleCreate(); }}
            onCancelEdit={handleHide}
            showActions={false}
          />
        ) : (
          <>
            <Form.Check
              type="switch"
              className="mb-3"
              label="Create initial schedule for this task"
              checked={createInitialSchedule}
              onChange={(event) => setCreateInitialSchedule(event.target.checked)}
              disabled={!featureEnabled}
            />

            {createInitialSchedule ? (
              <SchedulerCreateCard
                layout="plain"
                showTargetSelector={false}
                featureEnabled={featureEnabled}
                scheduledTasks={[pendingTask]}
                form={scheduleForm.form}
                isTimeValid={scheduleForm.isTimeValid}
                isCronValid={scheduleForm.isCronValid}
                isFormValid={scheduleForm.isFormValid}
                isCreating={scheduleBusy}
                isEditing={false}
                editingScheduleLabel={null}
                onTargetChange={scheduleForm.setTargetId}
                onModeChange={scheduleForm.setMode}
                onFrequencyChange={scheduleForm.setFrequency}
                onToggleDay={scheduleForm.toggleDay}
                onTimeChange={scheduleForm.setTime}
                onPresetTimeSelect={scheduleForm.setTime}
                onCronExpressionChange={scheduleForm.setCronExpression}
                onPresetCronSelect={scheduleForm.setCronExpression}
                onLimitInputChange={scheduleForm.setLimitInput}
                onPresetLimitSelect={scheduleForm.setLimitInput}
                onEnabledChange={scheduleForm.setEnabled}
                onClearContextBeforeRunChange={scheduleForm.setClearContextBeforeRun}
                onReportChannelTypeChange={scheduleForm.setReportChannelType}
                onReportChatIdChange={scheduleForm.setReportChatId}
                onWebhookUrlChange={scheduleForm.setReportWebhookUrl}
                onWebhookSecretChange={scheduleForm.setReportWebhookSecret}
                reportChannelOptions={reportChannelOptions}
                onSubmit={() => { void handleCreate(); }}
                onCancelEdit={handleHide}
              />
            ) : (
              <div className="small text-body-secondary">
                Leave this disabled if the task should exist without a schedule.
              </div>
            )}
          </>
        )}
      </Modal.Body>
      <Modal.Footer>
        <Button type="button" size="sm" variant="secondary" onClick={handleHide}>
          Cancel
        </Button>
        <Button
          type="button"
          size="sm"
          variant="primary"
          disabled={taskSubmitDisabled || scheduleSubmitDisabled || busy || scheduleBusy}
          onClick={() => { void handleCreate(); }}
        >
          {busy || scheduleBusy
            ? 'Saving...'
            : createInitialSchedule
              ? 'Create task & schedule'
              : 'Create task'}
        </Button>
      </Modal.Footer>
    </Modal>
  );
}
