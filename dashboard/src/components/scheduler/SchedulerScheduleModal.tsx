import type { ReactElement } from 'react';
import { Modal } from '../ui/tailwind-components';
import type { SchedulerScheduledTask } from '../../api/scheduler';
import type { useSchedulerForm } from '../../hooks/useSchedulerForm';
import type { ReportChannelOption } from './SchedulerCreateCardReportChannel';
import { SchedulerCreateCard } from './SchedulerCreateCard';

interface SchedulerScheduleModalProps {
  show: boolean;
  featureEnabled: boolean;
  scheduledTasks: SchedulerScheduledTask[];
  form: ReturnType<typeof useSchedulerForm>['form'];
  effectiveTargetId: string;
  isTimeValid: boolean;
  isCronValid: boolean;
  isFormValid: boolean;
  isSavingSchedule: boolean;
  isEditing: boolean;
  editingScheduleLabel: string | null;
  reportChannelOptions: ReportChannelOption[];
  onHide: () => void;
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
  onSubmit: () => void;
}

export function SchedulerScheduleModal({
  show,
  featureEnabled,
  scheduledTasks,
  form,
  effectiveTargetId,
  isTimeValid,
  isCronValid,
  isFormValid,
  isSavingSchedule,
  isEditing,
  editingScheduleLabel,
  reportChannelOptions,
  onHide,
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
  onSubmit,
}: SchedulerScheduleModalProps): ReactElement {
  return (
    <Modal show={show} onHide={onHide} size="lg" centered>
      <Modal.Header closeButton>
        <Modal.Title>{isEditing ? 'Edit schedule' : 'Create schedule'}</Modal.Title>
      </Modal.Header>
      <Modal.Body>
        <SchedulerCreateCard
          layout="plain"
          featureEnabled={featureEnabled}
          scheduledTasks={scheduledTasks}
          form={{ ...form, targetId: effectiveTargetId }}
          isTimeValid={isTimeValid}
          isCronValid={isCronValid}
          isFormValid={isFormValid}
          isCreating={isSavingSchedule}
          isEditing={isEditing}
          editingScheduleLabel={editingScheduleLabel}
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
          onSubmit={onSubmit}
          onCancelEdit={onHide}
        />
      </Modal.Body>
    </Modal>
  );
}
