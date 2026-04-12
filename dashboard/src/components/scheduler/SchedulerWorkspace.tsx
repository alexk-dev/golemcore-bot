import type { ReactElement, RefObject } from 'react';
import { Card, Col, Row } from 'react-bootstrap';
import type {
  SchedulerRunDetail,
  SchedulerRunSummary,
  SchedulerSchedule,
  SchedulerStateResponse,
} from '../../api/scheduler';
import type { useSchedulerForm } from '../../hooks/useSchedulerForm';
import type { ReportChannelOption } from './SchedulerCreateCardReportChannel';
import { SchedulerCreateCard } from './SchedulerCreateCard';
import { SchedulerRunLogsModal } from './SchedulerRunLogsModal';
import { SchedulerSchedulesCard } from './SchedulerSchedulesCard';
import { SchedulerStatusHeader } from './SchedulerStatusHeader';

export interface SchedulerWorkspaceProps {
  data: SchedulerStateResponse;
  form: ReturnType<typeof useSchedulerForm>['form'];
  effectiveTargetId: string;
  isTimeValid: boolean;
  isCronValid: boolean;
  isFormValid: boolean;
  isSavingSchedule: boolean;
  isBusy: boolean;
  editingScheduleLabel: string | null;
  logsSchedule: SchedulerSchedule | null;
  runs: SchedulerRunSummary[];
  runsLoading: boolean;
  selectedRunId: string | null;
  runDetail: SchedulerRunDetail | undefined;
  runDetailLoading: boolean;
  scheduleSectionRef: RefObject<HTMLDivElement>;
  onTargetTypeChange: ReturnType<typeof useSchedulerForm>['setTargetType'];
  onTargetChange: ReturnType<typeof useSchedulerForm>['setTargetId'];
  onModeChange: ReturnType<typeof useSchedulerForm>['setMode'];
  onFrequencyChange: ReturnType<typeof useSchedulerForm>['setFrequency'];
  onToggleDay: ReturnType<typeof useSchedulerForm>['toggleDay'];
  onTimeChange: ReturnType<typeof useSchedulerForm>['setTime'];
  onCronExpressionChange: ReturnType<typeof useSchedulerForm>['setCronExpression'];
  onLimitInputChange: ReturnType<typeof useSchedulerForm>['setLimitInput'];
  onEnabledChange: ReturnType<typeof useSchedulerForm>['setEnabled'];
  onClearContextBeforeRunChange: ReturnType<typeof useSchedulerForm>['setClearContextBeforeRun'];
  onReportChannelTypeChange: ReturnType<typeof useSchedulerForm>['setReportChannelType'];
  onReportChatIdChange: ReturnType<typeof useSchedulerForm>['setReportChatId'];
  onWebhookUrlChange: ReturnType<typeof useSchedulerForm>['setReportWebhookUrl'];
  onWebhookSecretChange: ReturnType<typeof useSchedulerForm>['setReportWebhookSecret'];
  reportChannelOptions: ReportChannelOption[];
  onSubmitSchedule: () => void;
  onCancelEditSchedule: () => void;
  onOpenLogs: (schedule: SchedulerSchedule) => void;
  onCloseLogs: () => void;
  onDeleteSchedule: (scheduleId: string) => void;
  onEditSchedule: (schedule: SchedulerSchedule) => void;
  onSelectRun: (runId: string) => void;
  resolveGoalHref: (goalId: string) => string | null;
  resolveTaskHref: (taskId: string) => string | null;
  resolveScheduleTargetHref: (schedule: SchedulerSchedule) => string | null;
}

export function SchedulerWorkspace({
  data,
  form,
  effectiveTargetId,
  isTimeValid,
  isCronValid,
  isFormValid,
  isSavingSchedule,
  isBusy,
  editingScheduleLabel,
  logsSchedule,
  runs,
  runsLoading,
  selectedRunId,
  runDetail,
  runDetailLoading,
  scheduleSectionRef,
  onTargetTypeChange,
  onTargetChange,
  onModeChange,
  onFrequencyChange,
  onToggleDay,
  onTimeChange,
  onCronExpressionChange,
  onLimitInputChange,
  onEnabledChange,
  onClearContextBeforeRunChange,
  onReportChannelTypeChange,
  onReportChatIdChange,
  onWebhookUrlChange,
  onWebhookSecretChange,
  reportChannelOptions,
  onSubmitSchedule,
  onCancelEditSchedule,
  onOpenLogs,
  onCloseLogs,
  onDeleteSchedule,
  onEditSchedule,
  onSelectRun,
  resolveGoalHref,
  resolveTaskHref,
  resolveScheduleTargetHref,
}: SchedulerWorkspaceProps): ReactElement {
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
          <div ref={scheduleSectionRef}>
            <SchedulerCreateCard
              featureEnabled={data.featureEnabled}
              goals={data.goals}
              standaloneTasks={data.standaloneTasks}
              form={{ ...form, targetId: effectiveTargetId }}
              isTimeValid={isTimeValid}
              isCronValid={isCronValid}
              isFormValid={isFormValid}
              isCreating={isSavingSchedule}
              isEditing={editingScheduleLabel != null}
              editingScheduleLabel={editingScheduleLabel}
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
              onEnabledChange={onEnabledChange}
              onClearContextBeforeRunChange={onClearContextBeforeRunChange}
              onReportChannelTypeChange={onReportChannelTypeChange}
              onReportChatIdChange={onReportChatIdChange}
              onWebhookUrlChange={onWebhookUrlChange}
              onWebhookSecretChange={onWebhookSecretChange}
              reportChannelOptions={reportChannelOptions}
              onSubmit={onSubmitSchedule}
              onCancelEdit={onCancelEditSchedule}
            />
          </div>
        </Col>

        <Col xl={7}>
          <SchedulerSchedulesCard
            schedules={data.schedules}
            busy={isBusy}
            resolveTargetHref={resolveScheduleTargetHref}
            onViewLogs={onOpenLogs}
            onEdit={onEditSchedule}
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
        resolveGoalHref={resolveGoalHref}
        resolveTaskHref={resolveTaskHref}
        onHide={onCloseLogs}
        onSelectRun={onSelectRun}
      />
    </div>
  );
}
