import { type ReactElement, useMemo, useState } from 'react';
import { Button, Card, Col, Row, Spinner } from 'react-bootstrap';
import {
  useCreateSchedule,
  useDeleteSchedule,
  useSchedulerBusyState,
  useSchedulerRun,
  useSchedulerRuns,
  useSchedulerState,
} from '../hooks/useScheduler';
import { useSchedulerForm } from '../hooks/useSchedulerForm';
import { SchedulerCreateCard } from '../components/scheduler/SchedulerCreateCard';
import { SchedulerRunLogsModal } from '../components/scheduler/SchedulerRunLogsModal';
import { SchedulerSchedulesCard } from '../components/scheduler/SchedulerSchedulesCard';
import { SchedulerStatusHeader } from '../components/scheduler/SchedulerStatusHeader';
import type { SchedulerRunDetail, SchedulerRunSummary, SchedulerSchedule, SchedulerStateResponse } from '../api/scheduler';

interface SchedulerPageContentProps {
  data: SchedulerStateResponse;
  goals: SchedulerStateResponse['goals'];
  form: ReturnType<typeof useSchedulerForm>['form'];
  effectiveTargetId: string;
  isTimeValid: boolean;
  isCronValid: boolean;
  isFormValid: boolean;
  isCreating: boolean;
  isBusy: boolean;
  logsSchedule: SchedulerSchedule | null;
  runs: SchedulerRunSummary[];
  runsLoading: boolean;
  selectedRunId: string | null;
  runDetail: SchedulerRunDetail | undefined;
  runDetailLoading: boolean;
  onTargetTypeChange: ReturnType<typeof useSchedulerForm>['setTargetType'];
  onTargetChange: ReturnType<typeof useSchedulerForm>['setTargetId'];
  onModeChange: ReturnType<typeof useSchedulerForm>['setMode'];
  onFrequencyChange: ReturnType<typeof useSchedulerForm>['setFrequency'];
  onToggleDay: ReturnType<typeof useSchedulerForm>['toggleDay'];
  onTimeChange: ReturnType<typeof useSchedulerForm>['setTime'];
  onCronExpressionChange: ReturnType<typeof useSchedulerForm>['setCronExpression'];
  onLimitInputChange: ReturnType<typeof useSchedulerForm>['setLimitInput'];
  onSubmit: () => void;
  onOpenLogs: (schedule: SchedulerSchedule) => void;
  onCloseLogs: () => void;
  onDeleteSchedule: (scheduleId: string) => void;
  onSelectRun: (runId: string) => void;
}

function resolveEffectiveRunId(runs: SchedulerRunSummary[], selectedRunId: string | null): string | null {
  const selectedRun = runs.find((run) => run.runId === selectedRunId);
  if (selectedRun != null) {
    return selectedRun.runId;
  }
  return runs[0]?.runId ?? null;
}

function SchedulerPageContent({
  data,
  goals,
  form,
  effectiveTargetId,
  isTimeValid,
  isCronValid,
  isFormValid,
  isCreating,
  isBusy,
  logsSchedule,
  runs,
  runsLoading,
  selectedRunId,
  runDetail,
  runDetailLoading,
  onTargetTypeChange,
  onTargetChange,
  onModeChange,
  onFrequencyChange,
  onToggleDay,
  onTimeChange,
  onCronExpressionChange,
  onLimitInputChange,
  onSubmit,
  onOpenLogs,
  onCloseLogs,
  onDeleteSchedule,
  onSelectRun,
}: SchedulerPageContentProps): ReactElement {
  return (
    <div className="dashboard-main">
      <SchedulerStatusHeader
        featureEnabled={data.featureEnabled}
        autoModeEnabled={data.autoModeEnabled}
      />

      {!data.featureEnabled && (
        <Card className="mb-3">
          <Card.Body className="text-body-secondary">
            Scheduler is unavailable because auto mode feature is disabled in runtime config.
          </Card.Body>
        </Card>
      )}

      <Row className="g-3">
        <Col xl={5}>
          <SchedulerCreateCard
            featureEnabled={data.featureEnabled}
            goals={goals}
            form={{ ...form, targetId: effectiveTargetId }}
            isTimeValid={isTimeValid}
            isCronValid={isCronValid}
            isFormValid={isFormValid}
            isCreating={isCreating}
            onTargetTypeChange={onTargetTypeChange}
            onTargetChange={onTargetChange}
            onModeChange={onModeChange}
            onFrequencyChange={onFrequencyChange}
            onToggleDay={onToggleDay}
            onTimeChange={onTimeChange}
            onPresetTimeSelect={onTimeChange}
            onCronExpressionChange={onCronExpressionChange}
            onPresetCronSelect={onCronExpressionChange}
            onLimitInputChange={onLimitInputChange}
            onPresetLimitSelect={onLimitInputChange}
            onSubmit={onSubmit}
          />
        </Col>

        <Col xl={7}>
          <SchedulerSchedulesCard
            schedules={data.schedules}
            busy={isBusy}
            onViewLogs={onOpenLogs}
            onDelete={onDeleteSchedule}
          />
        </Col>
      </Row>

      <SchedulerRunLogsModal
        show={logsSchedule != null}
        scheduleLabel={logsSchedule?.targetLabel ?? null}
        scheduleId={logsSchedule?.id ?? null}
        runs={runs}
        runsLoading={runsLoading}
        selectedRunId={selectedRunId}
        runDetail={runDetail}
        runDetailLoading={runDetailLoading}
        onHide={onCloseLogs}
        onSelectRun={onSelectRun}
      />
    </div>
  );
}

export default function SchedulerPage(): ReactElement {
  const schedulerQuery = useSchedulerState();
  const createScheduleMutation = useCreateSchedule();
  const deleteScheduleMutation = useDeleteSchedule();
  const [logsSchedule, setLogsSchedule] = useState<SchedulerSchedule | null>(null);
  const [selectedRunId, setSelectedRunId] = useState<string | null>(null);

  const data = schedulerQuery.data;
  const goals = useMemo(() => data?.goals ?? [], [data?.goals]);
  const {
    form,
    effectiveTargetId,
    isTimeValid,
    isCronValid,
    isFormValid,
    setTargetId,
    setMode,
    setFrequency,
    setTime,
    setCronExpression,
    setLimitInput,
    setTargetType,
    toggleDay,
    buildCreateRequest,
  } = useSchedulerForm(goals);

  const isBusy = useSchedulerBusyState([createScheduleMutation, deleteScheduleMutation]);
  const runsQuery = useSchedulerRuns(logsSchedule?.id ?? null, logsSchedule != null);
  const runs = runsQuery.data?.runs ?? [];
  const effectiveRunId = resolveEffectiveRunId(runs, selectedRunId);
  const runDetailQuery = useSchedulerRun(effectiveRunId, logsSchedule != null);

  const handleCreateSchedule = async (): Promise<void> => {
    const request = buildCreateRequest();
    if (request == null) {
      return;
    }
    await createScheduleMutation.mutateAsync(request);
  };

  if (schedulerQuery.isLoading) {
    return (
      <div className="dashboard-main">
        <div className="d-flex align-items-center gap-2 text-body-secondary">
          <Spinner size="sm" />
          <span>Loading scheduler...</span>
        </div>
      </div>
    );
  }

  if (schedulerQuery.isError || data == null) {
    return (
      <div className="dashboard-main">
        <Card className="text-center py-4">
          <Card.Body>
            <p className="text-danger mb-3">Failed to load scheduler state.</p>
            <Button type="button" variant="secondary" size="sm" onClick={() => { void schedulerQuery.refetch(); }}>
              Retry
            </Button>
          </Card.Body>
        </Card>
      </div>
    );
  }

  return (
    <SchedulerPageContent
      data={data}
      goals={goals}
      form={form}
      effectiveTargetId={effectiveTargetId}
      isTimeValid={isTimeValid}
      isCronValid={isCronValid}
      isFormValid={isFormValid}
      isCreating={createScheduleMutation.isPending}
      isBusy={isBusy}
      logsSchedule={logsSchedule}
      runs={runs}
      runsLoading={runsQuery.isLoading || runsQuery.isFetching}
      selectedRunId={effectiveRunId}
      runDetail={runDetailQuery.data}
      runDetailLoading={runDetailQuery.isLoading || runDetailQuery.isFetching}
      onTargetTypeChange={setTargetType}
      onTargetChange={setTargetId}
      onModeChange={setMode}
      onFrequencyChange={setFrequency}
      onToggleDay={toggleDay}
      onTimeChange={setTime}
      onCronExpressionChange={setCronExpression}
      onLimitInputChange={setLimitInput}
      onSubmit={() => { void handleCreateSchedule(); }}
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
      onSelectRun={setSelectedRunId}
    />
  );
}
