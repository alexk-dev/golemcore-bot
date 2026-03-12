import { useMemo, useState } from 'react';
import type { CreateScheduleRequest, SchedulerGoal } from '../api/scheduler';
import type { ScheduleFormState, SchedulerFrequency, SchedulerMode, SchedulerTargetType } from '../components/scheduler/schedulerTypes';
import { isValidTimeInput, normalizeTimeInput, parseLimitInput } from '../components/scheduler/schedulerFormUtils';

interface SchedulerFormStateResult {
  form: ScheduleFormState;
  effectiveTargetId: string;
  isTimeValid: boolean;
  isCronValid: boolean;
  isFormValid: boolean;
  setTargetId: (targetId: string) => void;
  setMode: (mode: SchedulerMode) => void;
  setFrequency: (frequency: SchedulerFrequency) => void;
  setTime: (value: string) => void;
  setCronExpression: (value: string) => void;
  setLimitInput: (value: string) => void;
  setTargetType: (targetType: SchedulerTargetType) => void;
  toggleDay: (day: number) => void;
  buildCreateRequest: () => CreateScheduleRequest | null;
}

function buildInitialFormState(): ScheduleFormState {
  return {
    targetType: 'GOAL',
    targetId: '',
    mode: 'simple',
    frequency: 'daily',
    days: [1],
    time: '09:00',
    cronExpression: '* * * * *',
    limitInput: '0',
  };
}

function isLikelyCronExpression(value: string): boolean {
  const trimmed = value.trim();
  if (trimmed.length === 0) {
    return false;
  }
  const parts = trimmed.split(/\s+/);
  return parts.length === 5 || parts.length === 6;
}

function resolveEffectiveTargetId(goals: SchedulerGoal[], form: ScheduleFormState): string {
  const targetIds = form.targetType === 'GOAL'
    ? goals.map((goal) => goal.id)
    : goals.flatMap((goal) => goal.tasks.map((task) => task.id));

  if (targetIds.includes(form.targetId)) {
    return form.targetId;
  }
  return targetIds[0] ?? '';
}

function toggleDaySelection(days: number[], day: number): number[] {
  if (days.includes(day)) {
    return days.filter((item) => item !== day);
  }
  return [...days, day].sort((left, right) => left - right);
}

function buildSimpleCreateRequest(
  form: ScheduleFormState,
  targetId: string,
  maxExecutions: number,
): CreateScheduleRequest {
  const isWeeklyFrequency = form.frequency === 'weekly' || form.frequency === 'custom';
  return {
    targetType: form.targetType,
    targetId,
    mode: 'simple',
    frequency: form.frequency,
    days: isWeeklyFrequency ? form.days : [],
    time: normalizeTimeInput(form.time),
    maxExecutions,
  };
}

function buildAdvancedCreateRequest(
  form: ScheduleFormState,
  targetId: string,
  maxExecutions: number,
): CreateScheduleRequest {
  return {
    targetType: form.targetType,
    targetId,
    mode: 'advanced',
    cronExpression: form.cronExpression.trim(),
    maxExecutions,
  };
}

export function useSchedulerForm(goals: SchedulerGoal[]): SchedulerFormStateResult {
  const [form, setForm] = useState<ScheduleFormState>(buildInitialFormState);

  const effectiveTargetId = useMemo(() => resolveEffectiveTargetId(goals, form), [form, goals]);
  const isTimeValid = form.mode === 'advanced' || isValidTimeInput(normalizeTimeInput(form.time));
  const isCronValid = form.mode === 'simple' || isLikelyCronExpression(form.cronExpression);
  const isWeeklyFrequency = form.frequency === 'weekly' || form.frequency === 'custom';
  const parsedLimit = parseLimitInput(form.limitInput);
  const hasValidDays = form.mode === 'advanced' || !isWeeklyFrequency || form.days.length > 0;
  const hasValidTarget = effectiveTargetId.length > 0;
  const isFormValid = hasValidTarget
    && parsedLimit != null
    && (form.mode === 'advanced' ? isCronValid : isTimeValid && hasValidDays);

  return {
    form,
    effectiveTargetId,
    isTimeValid,
    isCronValid,
    isFormValid,
    setTargetId: (targetId) => setForm((current) => ({ ...current, targetId })),
    setMode: (mode) => setForm((current) => ({ ...current, mode })),
    setFrequency: (frequency) => setForm((current) => ({ ...current, frequency })),
    setTime: (time) => setForm((current) => ({ ...current, time })),
    setCronExpression: (cronExpression) => setForm((current) => ({ ...current, cronExpression })),
    setLimitInput: (limitInput) => setForm((current) => ({ ...current, limitInput })),
    setTargetType: (targetType) => setForm((current) => ({ ...current, targetType, targetId: '' })),
    toggleDay: (day) => setForm((current) => ({ ...current, days: toggleDaySelection(current.days, day) })),
    buildCreateRequest: () => {
      if (!isFormValid || parsedLimit == null) {
        return null;
      }
      if (form.mode === 'advanced') {
        return buildAdvancedCreateRequest(form, effectiveTargetId, parsedLimit);
      }
      return buildSimpleCreateRequest(form, effectiveTargetId, parsedLimit);
    },
  };
}
