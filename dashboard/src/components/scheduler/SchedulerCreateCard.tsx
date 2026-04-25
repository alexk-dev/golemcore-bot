import type { ReactElement } from 'react';
import { Button, Card } from '../ui/tailwind-components';
import type { SchedulerScheduledTask } from '../../api/scheduler';
import type {
  SchedulerFrequency,
  SchedulerMode,
  ScheduleFormState,
} from './schedulerTypes';
import {
  buildScheduledTaskOptions,
  parseLimitInput,
  type SchedulerTargetOption,
} from './schedulerFormUtils';
import {
  AdvancedCronFields,
  ClearContextField,
  RepeatLimitField,
  ScheduleEnabledField,
  SchedulerModeToggle,
  SimpleScheduleFields,
  TargetSelector,
} from './SchedulerCreateCardSections';
import { ReportChannelField, type ReportChannelOption } from './SchedulerCreateCardReportChannel';

type SchedulerCreateLayout = 'card' | 'plain';

interface SchedulerCreateCardProps {
  featureEnabled: boolean;
  scheduledTasks: SchedulerScheduledTask[];
  form: ScheduleFormState;
  isTimeValid: boolean;
  isCronValid: boolean;
  isFormValid: boolean;
  isCreating: boolean;
  isEditing: boolean;
  editingScheduleLabel: string | null;
  onTargetChange: (targetId: string) => void;
  onModeChange: (mode: SchedulerMode) => void;
  onFrequencyChange: (frequency: SchedulerFrequency) => void;
  onToggleDay: (day: number) => void;
  onTimeChange: (value: string) => void;
  onPresetTimeSelect: (value: string) => void;
  onCronExpressionChange: (value: string) => void;
  onPresetCronSelect: (value: string) => void;
  onLimitInputChange: (value: string) => void;
  onPresetLimitSelect: (value: string) => void;
  onEnabledChange: (enabled: boolean) => void;
  onClearContextBeforeRunChange: (clearContextBeforeRun: boolean) => void;
  onReportChannelTypeChange: (reportChannelType: string) => void;
  onReportChatIdChange: (chatId: string) => void;
  onWebhookUrlChange: (url: string) => void;
  onWebhookSecretChange: (secret: string) => void;
  reportChannelOptions: ReportChannelOption[];
  onSubmit: () => void;
  onCancelEdit: () => void;
  layout?: SchedulerCreateLayout;
  showTargetSelector?: boolean;
}

interface SchedulerSubmitState {
  featureEnabled: boolean;
  isFormValid: boolean;
  isCreating: boolean;
  effectiveTargetId: string;
  parsedLimit: number | null;
}

function resolveTargetOptions(
  scheduledTasks: SchedulerScheduledTask[],
): SchedulerTargetOption[] {
  return buildScheduledTaskOptions(scheduledTasks);
}

function resolveEffectiveTargetId(options: SchedulerTargetOption[], targetId: string): string {
  const isPresent = options.some((option) => option.id === targetId);
  if (isPresent) {
    return targetId;
  }
  return options[0]?.id ?? '';
}

function shouldDisableSubmit(state: SchedulerSubmitState): boolean {
  if (!state.featureEnabled || !state.isFormValid || state.isCreating) {
    return true;
  }
  if (state.effectiveTargetId.length === 0) {
    return true;
  }
  return state.parsedLimit == null;
}

function resolveHeaderTitle(isEditing: boolean): string {
  return isEditing ? 'Edit schedule' : 'Create schedule';
}

function resolveSubmitLabel(isEditing: boolean, isCreating: boolean): string {
  if (isCreating) {
    return isEditing ? 'Saving...' : 'Creating...';
  }
  return isEditing ? 'Save schedule' : 'Create schedule';
}

function renderContent(
  layout: SchedulerCreateLayout,
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

export function SchedulerCreateCard({
  featureEnabled,
  scheduledTasks,
  form,
  isTimeValid,
  isCronValid,
  isFormValid,
  isCreating,
  isEditing,
  editingScheduleLabel,
  onTargetChange,
  onModeChange,
  onFrequencyChange,
  onToggleDay,
  onTimeChange,
  onPresetTimeSelect,
  onCronExpressionChange,
  onPresetCronSelect,
  onLimitInputChange,
  onPresetLimitSelect,
  onEnabledChange,
  onClearContextBeforeRunChange,
  onReportChannelTypeChange,
  onReportChatIdChange,
  onWebhookUrlChange,
  onWebhookSecretChange,
  reportChannelOptions,
  onSubmit,
  onCancelEdit,
  layout = 'card',
  showTargetSelector = true,
}: SchedulerCreateCardProps): ReactElement {
  const targetOptions = resolveTargetOptions(scheduledTasks);
  const effectiveTargetId = resolveEffectiveTargetId(targetOptions, form.targetId);
  const parsedLimit = parseLimitInput(form.limitInput);
  const submitDisabled = shouldDisableSubmit({
    featureEnabled,
    isFormValid,
    isCreating,
    effectiveTargetId,
    parsedLimit,
  });

  const content = (
    <>
      {isEditing && editingScheduleLabel != null && editingScheduleLabel.length > 0 && (
        <div className="small text-body-secondary mb-3">
          Editing target: <strong>{editingScheduleLabel}</strong>
        </div>
      )}

      {showTargetSelector && (
        <TargetSelector
          label="Scheduled task"
          helpText="Create a scheduled task first, then attach a schedule to it here."
          featureEnabled={featureEnabled}
          targetOptions={targetOptions}
          effectiveTargetId={effectiveTargetId}
          onTargetChange={onTargetChange}
        />
      )}

      <SchedulerModeToggle selected={form.mode} onChange={onModeChange} />

      {form.mode === 'simple' ? (
        <SimpleScheduleFields
          featureEnabled={featureEnabled}
          frequency={form.frequency}
          days={form.days}
          time={form.time}
          isTimeValid={isTimeValid}
          onFrequencyChange={onFrequencyChange}
          onToggleDay={onToggleDay}
          onTimeChange={onTimeChange}
          onPresetTimeSelect={onPresetTimeSelect}
        />
      ) : (
        <AdvancedCronFields
          featureEnabled={featureEnabled}
          cronExpression={form.cronExpression}
          isCronValid={isCronValid}
          onCronExpressionChange={onCronExpressionChange}
          onPresetCronSelect={onPresetCronSelect}
        />
      )}

      <RepeatLimitField
        featureEnabled={featureEnabled}
        limitInput={form.limitInput}
        onLimitInputChange={onLimitInputChange}
        onPresetLimitSelect={onPresetLimitSelect}
      />

      <ClearContextField
        featureEnabled={featureEnabled}
        clearContextBeforeRun={form.clearContextBeforeRun}
        onChange={onClearContextBeforeRunChange}
      />

      <ReportChannelField
        featureEnabled={featureEnabled}
        reportChannelType={form.reportChannelType}
        reportChatId={form.reportChatId}
        reportWebhookUrl={form.reportWebhookUrl}
        reportWebhookSecret={form.reportWebhookSecret}
        channelOptions={reportChannelOptions}
        onChange={onReportChannelTypeChange}
        onChatIdChange={onReportChatIdChange}
        onWebhookUrlChange={onWebhookUrlChange}
        onWebhookSecretChange={onWebhookSecretChange}
      />

      {isEditing && (
        <ScheduleEnabledField
          featureEnabled={featureEnabled}
          enabled={form.enabled}
          onEnabledChange={onEnabledChange}
        />
      )}

      <div className="d-flex gap-2 justify-content-end">
        {isEditing && (
          <Button type="button" variant="secondary" size="sm" onClick={onCancelEdit}>
            Cancel
          </Button>
        )}
        <Button
          type="button"
          variant="primary"
          size="sm"
          disabled={submitDisabled}
          onClick={onSubmit}
        >
          {resolveSubmitLabel(isEditing, isCreating)}
        </Button>
      </div>
    </>
  );

  return renderContent(layout, resolveHeaderTitle(isEditing), content);
}
