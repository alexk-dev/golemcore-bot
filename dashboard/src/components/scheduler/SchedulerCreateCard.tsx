import type { ReactElement } from 'react';
import { Button, Card } from 'react-bootstrap';
import type { SchedulerGoal } from '../../api/scheduler';
import type { SchedulerFrequency, SchedulerMode, SchedulerTargetType, ScheduleFormState } from './schedulerTypes';
import {
  buildGoalOptions,
  buildTaskOptions,
  parseLimitInput,
  type SchedulerTargetOption,
} from './schedulerFormUtils';
import {
  AdvancedCronFields,
  RepeatLimitField,
  SchedulerModeToggle,
  SimpleScheduleFields,
  TargetSelector,
  TargetTypeToggle,
} from './SchedulerCreateCardSections';

interface SchedulerCreateCardProps {
  featureEnabled: boolean;
  goals: SchedulerGoal[];
  form: ScheduleFormState;
  isTimeValid: boolean;
  isCronValid: boolean;
  isFormValid: boolean;
  isCreating: boolean;
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
  onSubmit: () => void;
}

interface SchedulerSubmitState {
  featureEnabled: boolean;
  isFormValid: boolean;
  isCreating: boolean;
  effectiveTargetId: string;
  parsedLimit: number | null;
}

function resolveTargetOptions(targetType: SchedulerTargetType, goals: SchedulerGoal[]): SchedulerTargetOption[] {
  if (targetType === 'GOAL') {
    return buildGoalOptions(goals);
  }
  return buildTaskOptions(goals);
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

export function SchedulerCreateCard({
  featureEnabled,
  goals,
  form,
  isTimeValid,
  isCronValid,
  isFormValid,
  isCreating,
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
  onSubmit,
}: SchedulerCreateCardProps): ReactElement {
  const targetOptions = resolveTargetOptions(form.targetType, goals);
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
      <Card.Header className="fw-semibold">Create schedule</Card.Header>
      <Card.Body>
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

        <Button
          type="button"
          variant="primary"
          size="sm"
          disabled={submitDisabled}
          onClick={onSubmit}
        >
          {isCreating ? 'Creating...' : 'Create schedule'}
        </Button>
      </Card.Body>
    </Card>
  );
}
