import type { ReactElement } from 'react';
import { Button, Card } from 'react-bootstrap';
import type { SchedulerGoal, SchedulerTask, SchedulerTargetType } from '../../api/scheduler';
import type {
  SchedulerFrequency,
  SchedulerMode,
  ScheduleFormState,
} from './schedulerTypes';
import {
  buildGoalOptions,
  buildTaskOptions,
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
  TargetTypeToggle,
} from './SchedulerCreateCardSections';
import { ReportChannelField, type ReportChannelOption } from './SchedulerCreateCardReportChannel';

interface SchedulerCreateCardProps {
  featureEnabled: boolean;
  goals: SchedulerGoal[];
  standaloneTasks: SchedulerTask[];
  form: ScheduleFormState;
  isTimeValid: boolean;
  isCronValid: boolean;
  isFormValid: boolean;
  isCreating: boolean;
  isEditing: boolean;
  editingScheduleLabel: string | null;
  onTargetTypeChange: (targetType: SchedulerTargetType) => void;
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
}

interface SchedulerSubmitState {
  featureEnabled: boolean;
  isFormValid: boolean;
  isCreating: boolean;
  effectiveTargetId: string;
  parsedLimit: number | null;
}

function resolveTargetOptions(
  targetType: SchedulerTargetType,
  goals: SchedulerGoal[],
  standaloneTasks: SchedulerTask[],
): SchedulerTargetOption[] {
  if (targetType === 'GOAL') {
    return buildGoalOptions(goals);
  }
  return buildTaskOptions(goals, standaloneTasks);
}

function resolveEffectiveTargetId(options: SchedulerTargetOption[], targetId: string): string {
  const isPresent = options.some((option) => option.id === targetId);
  if (isPresent) {
    return targetId;
  }
  return options[0]?.id ?? '';
}

function shouldDisableSubmit(state: SchedulerSubmitState): boolean {
  if (!state.featureEnabled) {
    return true;
  }
  if (!state.isFormValid) {
    return true;
  }
  if (state.isCreating) {
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

export function SchedulerCreateCard({
  featureEnabled,
  goals,
  standaloneTasks,
  form,
  isTimeValid,
  isCronValid,
  isFormValid,
  isCreating,
  isEditing,
  editingScheduleLabel,
  onTargetTypeChange,
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
}: SchedulerCreateCardProps): ReactElement {
  const targetOptions = resolveTargetOptions(form.targetType, goals, standaloneTasks);
  const effectiveTargetId = resolveEffectiveTargetId(targetOptions, form.targetId);
  const parsedLimit = parseLimitInput(form.limitInput);
  const submitDisabled = shouldDisableSubmit({
    featureEnabled,
    isFormValid,
    isCreating,
    effectiveTargetId,
    parsedLimit,
  });

  return (
    <Card className="h-100">
      <Card.Header className="fw-semibold">{resolveHeaderTitle(isEditing)}</Card.Header>
      <Card.Body>
        {isEditing && editingScheduleLabel != null && editingScheduleLabel.length > 0 && (
          <div className="small text-body-secondary mb-3">
            Editing target: <strong>{editingScheduleLabel}</strong>
          </div>
        )}

        <TargetTypeToggle selected={form.targetType} onChange={onTargetTypeChange} />

        <TargetSelector
          featureEnabled={featureEnabled}
          targetOptions={targetOptions}
          effectiveTargetId={effectiveTargetId}
          onTargetChange={onTargetChange}
        />

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

        <div className="d-flex gap-2">
          <Button
            type="button"
            variant="primary"
            size="sm"
            disabled={submitDisabled}
            onClick={onSubmit}
          >
            {resolveSubmitLabel(isEditing, isCreating)}
          </Button>
          {isEditing && (
            <Button type="button" variant="secondary" size="sm" onClick={onCancelEdit}>
              Cancel
            </Button>
          )}
        </div>
      </Card.Body>
    </Card>
  );
}
