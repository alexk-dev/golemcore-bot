import type { ReactElement } from 'react';
import { Card } from '../ui/tailwind-components';
import type {
  CreateScheduledTaskRequest,
  UpdateScheduledTaskRequest,
} from '../../api/scheduledTasks';
import type {
  CreateScheduleRequest,
  SchedulerRunDetail,
  SchedulerRunSummary,
  SchedulerSchedule,
  SchedulerStateResponse,
} from '../../api/scheduler';
import type { useSchedulerForm } from '../../hooks/useSchedulerForm';
import type { ReportChannelOption } from './SchedulerCreateCardReportChannel';
import { SchedulerRunLogsModal } from './SchedulerRunLogsModal';
import { SchedulerScheduleModal } from './SchedulerScheduleModal';
import { ScheduledTaskEditorModal } from './ScheduledTaskEditorModal';
import { ScheduledTaskListCard } from './ScheduledTaskListCard';
import { SchedulerStatusHeader } from './SchedulerStatusHeader';

export interface SchedulerWorkspaceProps {
  data: SchedulerStateResponse;
  form: ReturnType<typeof useSchedulerForm>['form'];
  effectiveTargetId: string;
  isTimeValid: boolean;
  isCronValid: boolean;
  isFormValid: boolean;
  isSavingSchedule: boolean;
  isSavingScheduledTask: boolean;
  isBusy: boolean;
  runningTaskId: string | null;
  editingScheduleLabel: string | null;
  editingScheduledTask: SchedulerStateResponse['scheduledTasks'][number] | null;
  logsSchedule: SchedulerSchedule | null;
  runs: SchedulerRunSummary[];
  runsLoading: boolean;
  selectedRunId: string | null;
  runDetail: SchedulerRunDetail | undefined;
  runDetailLoading: boolean;
  isScheduledTaskModalOpen: boolean;
  isScheduleModalOpen: boolean;
  onOpenCreateScheduledTask: () => void;
  onCloseScheduledTaskModal: () => void;
  onCloseScheduleModal: () => void;
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
  onCreateScheduledTask: (request: CreateScheduledTaskRequest) => Promise<SchedulerStateResponse['scheduledTasks'][number]>;
  onCreateScheduleRequest: (request: CreateScheduleRequest) => void | Promise<void>;
  onUpdateScheduledTask: (
    scheduledTaskId: string,
    request: UpdateScheduledTaskRequest,
  ) => Promise<void>;
  onDeleteScheduledTask: (scheduledTaskId: string) => void;
  onEditScheduledTask: (scheduledTask: SchedulerStateResponse['scheduledTasks'][number]) => void;
  onRunScheduledTaskNow: (scheduledTaskId: string) => void;
  onScheduleScheduledTask: (scheduledTaskId: string) => void;
  onSubmitSchedule: () => void;
  onOpenLogs: (schedule: SchedulerSchedule) => void;
  onCloseLogs: () => void;
  onDeleteSchedule: (scheduleId: string) => void;
  onEditSchedule: (schedule: SchedulerSchedule) => void;
  onSelectRun: (runId: string) => void;
  resolveGoalHref: (goalId: string) => string | null;
  resolveTaskHref: (taskId: string) => string | null;
  resolveScheduledTaskHref: (scheduledTaskId: string) => string | null;
}

export function SchedulerWorkspace({
  data,
  form,
  effectiveTargetId,
  isTimeValid,
  isCronValid,
  isFormValid,
  isSavingSchedule,
  isSavingScheduledTask,
  isBusy,
  runningTaskId,
  editingScheduleLabel,
  editingScheduledTask,
  logsSchedule,
  runs,
  runsLoading,
  selectedRunId,
  runDetail,
  runDetailLoading,
  isScheduledTaskModalOpen,
  isScheduleModalOpen,
  onOpenCreateScheduledTask,
  onCloseScheduledTaskModal,
  onCloseScheduleModal,
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
  onCreateScheduledTask,
  onCreateScheduleRequest,
  onUpdateScheduledTask,
  onDeleteScheduledTask,
  onEditScheduledTask,
  onRunScheduledTaskNow,
  onScheduleScheduledTask,
  onSubmitSchedule,
  onOpenLogs,
  onCloseLogs,
  onDeleteSchedule,
  onEditSchedule,
  onSelectRun,
  resolveGoalHref,
  resolveTaskHref,
  resolveScheduledTaskHref,
}: SchedulerWorkspaceProps): ReactElement {
  return (
    <div className="dashboard-main">
      <SchedulerStatusHeader />

      {!data.featureEnabled && (
        <Card className="mb-3">
          <Card.Body className="text-body-secondary">
            Scheduler is unavailable because auto mode feature is disabled in runtime config.
          </Card.Body>
        </Card>
      )}

      <div className="d-flex flex-column gap-3">
        <ScheduledTaskListCard
          scheduledTasks={data.scheduledTasks}
          schedules={data.schedules}
          busy={isBusy}
          runningTaskId={runningTaskId}
          onCreate={onOpenCreateScheduledTask}
          onRunNow={onRunScheduledTaskNow}
          onSchedule={onScheduleScheduledTask}
          onEdit={onEditScheduledTask}
          onDelete={onDeleteScheduledTask}
          onViewLogs={onOpenLogs}
          onEditSchedule={onEditSchedule}
          onDeleteSchedule={onDeleteSchedule}
        />
      </div>

      <ScheduledTaskEditorModal
        show={isScheduledTaskModalOpen}
        featureEnabled={data.featureEnabled}
        busy={isSavingScheduledTask}
        scheduleBusy={isSavingSchedule}
        task={editingScheduledTask}
        reportChannelOptions={reportChannelOptions}
        onHide={onCloseScheduledTaskModal}
        onCreate={onCreateScheduledTask}
        onCreateSchedule={onCreateScheduleRequest}
        onUpdate={onUpdateScheduledTask}
      />

      <SchedulerScheduleModal
        show={isScheduleModalOpen}
        featureEnabled={data.featureEnabled}
        scheduledTasks={data.scheduledTasks}
        form={form}
        effectiveTargetId={effectiveTargetId}
        isTimeValid={isTimeValid}
        isCronValid={isCronValid}
        isFormValid={isFormValid}
        isSavingSchedule={isSavingSchedule}
        isEditing={editingScheduleLabel != null}
        editingScheduleLabel={editingScheduleLabel}
        reportChannelOptions={reportChannelOptions}
        onHide={onCloseScheduleModal}
        onTargetChange={onTargetChange}
        onModeChange={onModeChange}
        onFrequencyChange={onFrequencyChange}
        onToggleDay={onToggleDay}
        onTimeChange={onTimeChange}
        onCronExpressionChange={onCronExpressionChange}
        onLimitInputChange={onLimitInputChange}
        onEnabledChange={onEnabledChange}
        onClearContextBeforeRunChange={onClearContextBeforeRunChange}
        onReportChannelTypeChange={onReportChannelTypeChange}
        onReportChatIdChange={onReportChatIdChange}
        onWebhookUrlChange={onWebhookUrlChange}
        onWebhookSecretChange={onWebhookSecretChange}
        onSubmit={onSubmitSchedule}
      />

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
        resolveScheduledTaskHref={resolveScheduledTaskHref}
        onHide={onCloseLogs}
        onSelectRun={onSelectRun}
      />
    </div>
  );
}
