import { type ReactElement, useMemo, useState } from 'react';
import { Button, Card, Col, Row, Spinner } from 'react-bootstrap';
import { useCreateSchedule, useDeleteSchedule, useSchedulerBusyState, useSchedulerState } from '../hooks/useScheduler';
import { SchedulerCreateCard } from '../components/scheduler/SchedulerCreateCard';
import { SchedulerSchedulesCard } from '../components/scheduler/SchedulerSchedulesCard';
import { SchedulerStatusHeader } from '../components/scheduler/SchedulerStatusHeader';
import type { ScheduleFormState, SchedulerFrequency, SchedulerTargetType } from '../components/scheduler/schedulerTypes';
import { isValidTimeInput, normalizeTimeInput, parseLimitInput } from '../components/scheduler/schedulerFormUtils';

export default function SchedulerPage(): ReactElement {
  const schedulerQuery = useSchedulerState();
  const createScheduleMutation = useCreateSchedule();
  const deleteScheduleMutation = useDeleteSchedule();

  const [form, setForm] = useState<ScheduleFormState>({
    targetType: 'GOAL',
    targetId: '',
    frequency: 'daily',
    days: [1],
    time: '09:00',
    limitInput: '0',
  });

  const data = schedulerQuery.data;
  const goals = useMemo(() => data?.goals ?? [], [data?.goals]);

  const targetOptions = useMemo(() => {
    if (form.targetType === 'GOAL') {
      return goals.map((goal) => ({ id: goal.id }));
    }
    return goals.flatMap((goal) => goal.tasks.map((task) => ({ id: task.id })));
  }, [form.targetType, goals]);

  const effectiveTargetId = useMemo(() => {
    if (targetOptions.some((option) => option.id === form.targetId)) {
      return form.targetId;
    }
    return targetOptions[0]?.id ?? '';
  }, [form.targetId, targetOptions]);

  const isWeeklyFrequency = form.frequency === 'weekly' || form.frequency === 'custom';
  const normalizedTime = normalizeTimeInput(form.time);
  const parsedLimit = parseLimitInput(form.limitInput);
  const isTimeValid = isValidTimeInput(normalizedTime);
  const hasValidDays = !isWeeklyFrequency || form.days.length > 0;
  const hasValidTarget = effectiveTargetId.length > 0;
  const isFormValid = isTimeValid && hasValidDays && hasValidTarget && parsedLimit != null;

  const isBusy = useSchedulerBusyState([createScheduleMutation, deleteScheduleMutation]);

  const handleTargetTypeChange = (targetType: SchedulerTargetType): void => {
    setForm((current) => ({
      ...current,
      targetType,
      targetId: '',
    }));
  };

  const handleFrequencyChange = (frequency: SchedulerFrequency): void => {
    setForm((current) => ({
      ...current,
      frequency,
    }));
  };

  const handleToggleDay = (day: number): void => {
    setForm((current) => {
      if (current.days.includes(day)) {
        return {
          ...current,
          days: current.days.filter((item) => item !== day),
        };
      }
      return {
        ...current,
        days: [...current.days, day].sort((left, right) => left - right),
      };
    });
  };

  const handleCreateSchedule = async (): Promise<void> => {
    if (!isFormValid || parsedLimit == null) {
      return;
    }

    await createScheduleMutation.mutateAsync({
      targetType: form.targetType,
      targetId: effectiveTargetId,
      frequency: form.frequency,
      days: isWeeklyFrequency ? form.days : [],
      time: normalizedTime,
      maxExecutions: parsedLimit,
    });
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
            isFormValid={isFormValid}
            isCreating={createScheduleMutation.isPending}
            onTargetTypeChange={handleTargetTypeChange}
            onTargetChange={(targetId) => setForm((current) => ({ ...current, targetId }))}
            onFrequencyChange={handleFrequencyChange}
            onToggleDay={handleToggleDay}
            onTimeChange={(value) => setForm((current) => ({ ...current, time: value }))}
            onPresetTimeSelect={(value) => setForm((current) => ({ ...current, time: value }))}
            onLimitInputChange={(value) => setForm((current) => ({ ...current, limitInput: value }))}
            onPresetLimitSelect={(value) => setForm((current) => ({ ...current, limitInput: value }))}
            onSubmit={() => { void handleCreateSchedule(); }}
          />
        </Col>

        <Col xl={7}>
          <SchedulerSchedulesCard
            schedules={data.schedules}
            busy={isBusy}
            onDelete={(scheduleId) => {
              void deleteScheduleMutation.mutateAsync(scheduleId);
            }}
          />
        </Col>
      </Row>
    </div>
  );
}
