import { type ReactElement, useEffect, useMemo, useRef, useState } from 'react';
import { Button, Card, Spinner } from 'react-bootstrap';
import { useSearchParams } from 'react-router-dom';
import type { SchedulerRunSummary, SchedulerSchedule, SchedulerTargetType } from '../api/scheduler';
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
import { useSchedulerForm } from '../hooks/useSchedulerForm';
import { useSchedulerNavigation } from '../hooks/useSchedulerNavigation';
import { useRuntimeConfig } from '../hooks/useSettings';
import { useSystemChannels } from '../hooks/useSystem';
import { filterAndSortChannels, resolveLinkedTelegramUserId } from '../utils/channelUtils';

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
  return value === 'GOAL' || value === 'TASK';
}

export default function SchedulerPage(): ReactElement {
  const schedulerQuery = useSchedulerState();
  const createScheduleMutation = useCreateSchedule();
  const updateScheduleMutation = useUpdateSchedule();
  const deleteScheduleMutation = useDeleteSchedule();
  const [searchParams, setSearchParams] = useSearchParams();
  const [logsSchedule, setLogsSchedule] = useState<SchedulerSchedule | null>(null);
  const [selectedRunId, setSelectedRunId] = useState<string | null>(null);
  const scheduleSectionRef = useRef<HTMLDivElement>(null);

  const data = schedulerQuery.data;
  const goals = useMemo(() => data?.goals ?? [], [data?.goals]);
  const standaloneTasks = useMemo(() => data?.standaloneTasks ?? [], [data?.standaloneTasks]);
  const formState = useSchedulerForm(goals, standaloneTasks);
  const runtimeConfigQuery = useRuntimeConfig();
  const systemChannelsQuery = useSystemChannels();
  const linkedTelegramUserId = useMemo(
    () => resolveLinkedTelegramUserId(runtimeConfigQuery.data?.telegram?.allowedUsers),
    [runtimeConfigQuery.data?.telegram?.allowedUsers],
  );
  const channelOptions = useMemo(() => {
    const excluded = new Set(['web']);
    return filterAndSortChannels(systemChannelsQuery.data ?? [], excluded)
      .map((channel) => ({
        type: channel.type,
        label: channel.type.charAt(0).toUpperCase() + channel.type.slice(1),
      }));
  }, [systemChannelsQuery.data]);

  const handleReportChannelTypeChange = (channelType: string): void => {
    formState.setReportChannelType(channelType);
    if (channelType === 'telegram' && linkedTelegramUserId != null) {
      formState.setReportChatId(linkedTelegramUserId);
    }
  };
  const navigation = useSchedulerNavigation(
    goals,
    standaloneTasks,
    scheduleSectionRef,
    formState.startEditing,
  );

  const isBusy = useSchedulerBusyState([
    createScheduleMutation,
    updateScheduleMutation,
    deleteScheduleMutation,
  ]);
  const isSavingSchedule = createScheduleMutation.isPending || updateScheduleMutation.isPending;
  const runsQuery = useSchedulerRuns(logsSchedule?.id ?? null, logsSchedule != null);
  const runs = runsQuery.data?.runs ?? [];
  const effectiveRunId = resolveEffectiveRunId(runs, selectedRunId);
  const runDetailQuery = useSchedulerRun(effectiveRunId, logsSchedule != null);

  const editingScheduleLabel = useMemo(() => {
    if (formState.editingScheduleId == null || data == null) {
      return null;
    }
    return data.schedules.find((schedule) => schedule.id === formState.editingScheduleId)?.targetLabel ?? null;
  }, [data, formState.editingScheduleId]);

  useEffect(() => {
    // Consume explicit prefill params from the Goals page once, then return to a clean scheduler URL.
    const targetType = searchParams.get('targetType');
    const targetId = searchParams.get('targetId');
    if (!isSchedulerTargetType(targetType) || targetId == null || targetId.length === 0) {
      return;
    }
    formState.prepareCreateForTarget(targetType, targetId);
    setSearchParams({}, { replace: true });
  }, [formState, searchParams, setSearchParams]);

  const handleSubmitSchedule = async (): Promise<void> => {
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
  };

  if (schedulerQuery.isLoading) {
    return <LoadingState />;
  }

  if (schedulerQuery.isError || data == null) {
    return <ErrorState onRetry={() => { void schedulerQuery.refetch(); }} />;
  }

  return (
    <SchedulerWorkspace
      data={data}
      form={formState.form}
      effectiveTargetId={formState.effectiveTargetId}
      isTimeValid={formState.isTimeValid}
      isCronValid={formState.isCronValid}
      isFormValid={formState.isFormValid}
      isSavingSchedule={isSavingSchedule}
      isBusy={isBusy}
      editingScheduleLabel={editingScheduleLabel}
      logsSchedule={logsSchedule}
      runs={runs}
      runsLoading={runsQuery.isLoading || runsQuery.isFetching}
      selectedRunId={effectiveRunId}
      runDetail={runDetailQuery.data}
      runDetailLoading={runDetailQuery.isLoading || runDetailQuery.isFetching}
      scheduleSectionRef={scheduleSectionRef}
      onTargetTypeChange={formState.setTargetType}
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
      reportChannelOptions={channelOptions}
      onSubmitSchedule={() => { void handleSubmitSchedule(); }}
      onCancelEditSchedule={formState.reset}
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
      resolveScheduleTargetHref={navigation.resolveScheduleTargetHref}
    />
  );
}
