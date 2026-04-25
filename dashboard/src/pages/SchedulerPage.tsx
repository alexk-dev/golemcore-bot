import { type ReactElement, useEffect, useMemo, useState } from 'react';
import { Button, Card, Spinner } from '../components/ui/tailwind-components';
import { useSearchParams } from 'react-router-dom';
import type {
  CreateScheduledTaskRequest,
  UpdateScheduledTaskRequest,
} from '../api/scheduledTasks';
import type {
  SchedulerReportChannelOption,
  SchedulerRunSummary,
  SchedulerSchedule,
  SchedulerScheduledTask,
  SchedulerTargetType,
} from '../api/scheduler';
import { SchedulerWorkspace } from '../components/scheduler/SchedulerWorkspace';
import {
  useCreateSchedule,
  useDeleteSchedule,
  useSchedulerBusyState,
  useSchedulerRun,
  useSchedulerRuns,
  useSchedulerState,
  useUpdateSchedule,
} from '../hooks/useScheduler';
import {
  useCreateScheduledTask,
  useDeleteScheduledTask,
  useRunScheduledTaskNow,
  useUpdateScheduledTask,
} from '../hooks/useScheduledTasks';
import { useSchedulerForm } from '../hooks/useSchedulerForm';
import { useSchedulerNavigation } from '../hooks/useSchedulerNavigation';

function resolveEffectiveRunId(runs: SchedulerRunSummary[], selectedRunId: string | null): string | null {
  const selectedRun = runs.find((run) => run.runId === selectedRunId);
  if (selectedRun != null) {
    return selectedRun.runId;
  }
  return runs[0]?.runId ?? null;
}

function LoadingState(): ReactElement {
  return (
    <div className="dashboard-main">
      <div className="d-flex align-items-center gap-2 text-body-secondary">
        <Spinner size="sm" />
        <span>Loading scheduler...</span>
      </div>
    </div>
  );
}

interface ErrorStateProps {
  onRetry: () => void;
}

function ErrorState({ onRetry }: ErrorStateProps): ReactElement {
  return (
    <div className="dashboard-main">
      <Card className="text-center py-4">
        <Card.Body>
          <p className="text-danger mb-3">Failed to load scheduler state.</p>
          <Button type="button" variant="secondary" size="sm" onClick={onRetry}>
            Retry
          </Button>
        </Card.Body>
      </Card>
    </div>
  );
}

function isSchedulerTargetType(value: string | null): value is SchedulerTargetType {
  return value === 'GOAL' || value === 'TASK' || value === 'SCHEDULED_TASK';
}

function resolveEditingScheduleLabel(
  scheduleData: ReturnType<typeof useSchedulerState>['data'],
  editingScheduleId: string | null,
): string | null {
  if (editingScheduleId == null || scheduleData == null) {
    return null;
  }
  return scheduleData.schedules.find((schedule) => schedule.id === editingScheduleId)?.targetLabel ?? null;
}

function resolveSuggestedChatId(
  channelType: string,
  options: SchedulerReportChannelOption[],
): string | null {
  return options.find((option) => option.type === channelType)?.suggestedChatId ?? null;
}

async function submitSchedule(
  formState: ReturnType<typeof useSchedulerForm>,
  createScheduleMutation: ReturnType<typeof useCreateSchedule>,
  updateScheduleMutation: ReturnType<typeof useUpdateSchedule>,
): Promise<void> {
  if (formState.editingScheduleId != null) {
    const updatePayload = formState.buildUpdateRequest();
    if (updatePayload == null) {
      return;
    }
    await updateScheduleMutation.mutateAsync(updatePayload);
    formState.reset();
    return;
  }

  const createPayload = formState.buildCreateRequest();
  if (createPayload == null) {
    return;
  }
  await createScheduleMutation.mutateAsync(createPayload);
}

function applyReportChannelTypeChange(
  formState: ReturnType<typeof useSchedulerForm>,
  channelType: string,
  options: SchedulerReportChannelOption[],
): void {
  formState.setReportChannelType(channelType);
  const suggestedChatId = resolveSuggestedChatId(channelType, options);
  if (suggestedChatId != null && suggestedChatId.length > 0) {
    formState.setReportChatId(suggestedChatId);
  }
}

function handleScheduledTaskDeletion(
  scheduledTaskId: string,
  editingScheduledTaskId: string | null,
  setEditingScheduledTaskId: (scheduledTaskId: string | null) => void,
  deleteScheduledTaskMutation: ReturnType<typeof useDeleteScheduledTask>,
): void {
  if (editingScheduledTaskId === scheduledTaskId) {
    setEditingScheduledTaskId(null);
  }
  void deleteScheduledTaskMutation.mutateAsync(scheduledTaskId);
}

function restoreSchedulerAnchor(
  scheduleData: ReturnType<typeof useSchedulerState>['data'],
): void {
  if (scheduleData == null) {
    return;
  }
  const hash = window.location.hash.slice(1);
  if (hash.length === 0) {
    return;
  }
  window.requestAnimationFrame(() => {
    document.getElementById(hash)?.scrollIntoView({ block: 'start' });
  });
}

function applySchedulerPrefill(
  searchParams: URLSearchParams,
  formState: ReturnType<typeof useSchedulerForm>,
  setSearchParams: ReturnType<typeof useSearchParams>[1],
  openScheduleModal: () => void,
): void {
  const targetType = searchParams.get('targetType');
  const targetId = searchParams.get('targetId');
  if (!isSchedulerTargetType(targetType) || targetId == null || targetId.length === 0) {
    return;
  }
  if (targetType !== 'SCHEDULED_TASK') {
    return;
  }
  formState.prepareCreateForTarget(targetType, targetId);
  openScheduleModal();
  setSearchParams({}, { replace: true });
}

function isScheduledTaskSavePending(
  createScheduledTaskMutation: ReturnType<typeof useCreateScheduledTask>,
  updateScheduledTaskMutation: ReturnType<typeof useUpdateScheduledTask>,
): boolean {
  return createScheduledTaskMutation.isPending || updateScheduledTaskMutation.isPending;
}

function hasSchedulerData(
  scheduleData: ReturnType<typeof useSchedulerState>['data'],
): scheduleData is NonNullable<ReturnType<typeof useSchedulerState>['data']> {
  return scheduleData != null;
}

function resolveRunningTaskId(
  mutation: ReturnType<typeof useRunScheduledTaskNow>,
): string | null {
  return mutation.isPending ? mutation.variables ?? null : null;
}

function renderQueryState(
  schedulerQuery: ReturnType<typeof useSchedulerState>,
  data: ReturnType<typeof useSchedulerState>['data'],
): ReactElement | null {
  if (schedulerQuery.isLoading) {
    return <LoadingState />;
  }

  if (schedulerQuery.isError || !hasSchedulerData(data)) {
    return <ErrorState onRetry={() => { void schedulerQuery.refetch(); }} />;
  }

  return null;
}

export default function SchedulerPage(): ReactElement {
  const schedulerQuery = useSchedulerState();
  const createScheduledTaskMutation = useCreateScheduledTask();
  const updateScheduledTaskMutation = useUpdateScheduledTask();
  const deleteScheduledTaskMutation = useDeleteScheduledTask();
  const runScheduledTaskNowMutation = useRunScheduledTaskNow();
  const createScheduleMutation = useCreateSchedule();
  const updateScheduleMutation = useUpdateSchedule();
  const deleteScheduleMutation = useDeleteSchedule();
  const [searchParams, setSearchParams] = useSearchParams();
  const [logsSchedule, setLogsSchedule] = useState<SchedulerSchedule | null>(null);
  const [selectedRunId, setSelectedRunId] = useState<string | null>(null);
  const [editingScheduledTaskId, setEditingScheduledTaskId] = useState<string | null>(null);
  const [isScheduledTaskModalOpen, setScheduledTaskModalOpen] = useState(false);
  const [isScheduleModalOpen, setScheduleModalOpen] = useState(false);

  const data = schedulerQuery.data;
  const reportChannelOptions = useMemo(
    () => data?.reportChannelOptions ?? [],
    [data?.reportChannelOptions],
  );
  const goals = useMemo(() => data?.goals ?? [], [data?.goals]);
  const standaloneTasks = useMemo(() => data?.standaloneTasks ?? [], [data?.standaloneTasks]);
  const scheduledTasks = useMemo(() => data?.scheduledTasks ?? [], [data?.scheduledTasks]);
  const editingScheduledTask = useMemo(
    () => scheduledTasks.find((task) => task.id === editingScheduledTaskId) ?? null,
    [editingScheduledTaskId, scheduledTasks],
  );
  const formState = useSchedulerForm(scheduledTasks);

  const handleReportChannelTypeChange = (channelType: string): void => {
    applyReportChannelTypeChange(formState, channelType, reportChannelOptions);
  };
  const navigation = useSchedulerNavigation(
    goals,
    standaloneTasks,
    scheduledTasks,
    (schedule) => {
      formState.startEditing(schedule);
      setScheduleModalOpen(true);
    },
  );

  const isBusy = useSchedulerBusyState([
    createScheduledTaskMutation,
    updateScheduledTaskMutation,
    deleteScheduledTaskMutation,
    createScheduleMutation,
    updateScheduleMutation,
    deleteScheduleMutation,
  ]);
  const isSavingSchedule = createScheduleMutation.isPending || updateScheduleMutation.isPending;
  const isSavingScheduledTask = isScheduledTaskSavePending(
    createScheduledTaskMutation,
    updateScheduledTaskMutation,
  );
  const runningTaskId = resolveRunningTaskId(runScheduledTaskNowMutation);
  const runsQuery = useSchedulerRuns(logsSchedule?.id ?? null, logsSchedule != null);
  const runs = runsQuery.data?.runs ?? [];
  const effectiveRunId = resolveEffectiveRunId(runs, selectedRunId);
  const runDetailQuery = useSchedulerRun(effectiveRunId, logsSchedule != null);

  const editingScheduleLabel = useMemo(
    () => resolveEditingScheduleLabel(data, formState.editingScheduleId),
    [data, formState.editingScheduleId],
  );

  useEffect(() => {
    restoreSchedulerAnchor(data);
  }, [data]);

  useEffect(() => {
    applySchedulerPrefill(searchParams, formState, setSearchParams, () => setScheduleModalOpen(true));
  }, [formState, searchParams, setSearchParams]);

  const openCreateScheduledTaskModal = (): void => {
    setEditingScheduledTaskId(null);
    setScheduledTaskModalOpen(true);
  };

  const closeScheduledTaskModal = (): void => {
    setEditingScheduledTaskId(null);
    setScheduledTaskModalOpen(false);
  };

  const closeScheduleModal = (): void => {
    formState.reset();
    setScheduleModalOpen(false);
  };

  const handleScheduleTaskSelection = (scheduledTaskId: string): void => {
    formState.prepareCreateForTarget('SCHEDULED_TASK', scheduledTaskId);
    setScheduleModalOpen(true);
  };

  const handleCreateScheduledTask = async (
    taskRequest: CreateScheduledTaskRequest,
  ): Promise<SchedulerScheduledTask> => createScheduledTaskMutation.mutateAsync(taskRequest);

  const handleCreateScheduleRequest = async (request: Parameters<typeof createScheduleMutation.mutateAsync>[0]): Promise<void> => {
    await createScheduleMutation.mutateAsync(request);
  };

  const handleUpdateScheduledTask = async (
    scheduledTaskId: string,
    taskRequest: UpdateScheduledTaskRequest,
  ): Promise<void> => {
    await updateScheduledTaskMutation.mutateAsync({ scheduledTaskId, request: taskRequest });
    closeScheduledTaskModal();
  };

  const handleDeleteScheduledTask = (scheduledTaskId: string): void => {
    handleScheduledTaskDeletion(
      scheduledTaskId,
      editingScheduledTaskId,
      setEditingScheduledTaskId,
      deleteScheduledTaskMutation,
    );
  };

  const handleSubmitSchedule = async (): Promise<void> => {
    await submitSchedule(formState, createScheduleMutation, updateScheduleMutation);
    closeScheduleModal();
  };

  const queryState = renderQueryState(schedulerQuery, data);
  if (queryState != null) {
    return queryState;
  }

  if (!hasSchedulerData(data)) {
    return <ErrorState onRetry={() => { void schedulerQuery.refetch(); }} />;
  }

  const schedulerData = data;

  return (
    <SchedulerWorkspace
      data={schedulerData}
      form={formState.form}
      effectiveTargetId={formState.effectiveTargetId}
      isTimeValid={formState.isTimeValid}
      isCronValid={formState.isCronValid}
      isFormValid={formState.isFormValid}
      isSavingSchedule={isSavingSchedule}
      isSavingScheduledTask={isSavingScheduledTask}
      isBusy={isBusy}
      runningTaskId={runningTaskId}
      editingScheduleLabel={editingScheduleLabel}
      editingScheduledTask={editingScheduledTask}
      logsSchedule={logsSchedule}
      runs={runs}
      runsLoading={runsQuery.isLoading || runsQuery.isFetching}
      selectedRunId={effectiveRunId}
      runDetail={runDetailQuery.data}
      runDetailLoading={runDetailQuery.isLoading || runDetailQuery.isFetching}
      isScheduledTaskModalOpen={isScheduledTaskModalOpen}
      isScheduleModalOpen={isScheduleModalOpen}
      onOpenCreateScheduledTask={openCreateScheduledTaskModal}
      onCloseScheduledTaskModal={closeScheduledTaskModal}
      onCloseScheduleModal={closeScheduleModal}
      onTargetChange={formState.setTargetId}
      onModeChange={formState.setMode}
      onFrequencyChange={formState.setFrequency}
      onToggleDay={formState.toggleDay}
      onTimeChange={formState.setTime}
      onCronExpressionChange={formState.setCronExpression}
      onLimitInputChange={formState.setLimitInput}
      onEnabledChange={formState.setEnabled}
      onClearContextBeforeRunChange={formState.setClearContextBeforeRun}
      onReportChannelTypeChange={handleReportChannelTypeChange}
      onReportChatIdChange={formState.setReportChatId}
      onWebhookUrlChange={formState.setReportWebhookUrl}
      onWebhookSecretChange={formState.setReportWebhookSecret}
      reportChannelOptions={reportChannelOptions}
      onCreateScheduledTask={handleCreateScheduledTask}
      onCreateScheduleRequest={handleCreateScheduleRequest}
      onUpdateScheduledTask={handleUpdateScheduledTask}
      onDeleteScheduledTask={handleDeleteScheduledTask}
      onEditScheduledTask={(scheduledTask: SchedulerScheduledTask) => {
        setEditingScheduledTaskId(scheduledTask.id);
        setScheduledTaskModalOpen(true);
      }}
      onRunScheduledTaskNow={(scheduledTaskId) => {
        void runScheduledTaskNowMutation.mutateAsync(scheduledTaskId);
      }}
      onScheduleScheduledTask={handleScheduleTaskSelection}
      onSubmitSchedule={() => {
        void handleSubmitSchedule();
      }}
      onOpenLogs={(schedule) => {
        setLogsSchedule(schedule);
        setSelectedRunId(null);
      }}
      onCloseLogs={() => {
        setLogsSchedule(null);
        setSelectedRunId(null);
      }}
      onDeleteSchedule={(scheduleId) => {
        void deleteScheduleMutation.mutateAsync(scheduleId);
      }}
      onEditSchedule={navigation.openScheduleEditor}
      onSelectRun={setSelectedRunId}
      resolveGoalHref={navigation.resolveGoalHref}
      resolveTaskHref={navigation.resolveTaskHref}
      resolveScheduledTaskHref={navigation.resolveScheduledTaskHref}
    />
  );
}
