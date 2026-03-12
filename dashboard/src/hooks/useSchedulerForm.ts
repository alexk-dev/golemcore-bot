import { useMemo, useState } from 'react';
import type {
  CreateScheduleRequest,
  SchedulerGoal,
  SchedulerSchedule,
  SchedulerTask,
  UpdateScheduleRequest,
} from '../api/scheduler';
import type {
  ScheduleFormState,
  SchedulerFrequency,
  SchedulerMode,
  SchedulerTargetType,
} from '../components/scheduler/schedulerTypes';
import {
  isValidTimeInput,
  normalizeTimeInput,
  parseLimitInput,
} from '../components/scheduler/schedulerFormUtils';

const CRON_DAILY_PATTERN = /^0 (\d{1,2}) (\d{1,2}) \* \* \*$/;
const CRON_WEEKDAYS_PATTERN = /^0 (\d{1,2}) (\d{1,2}) \* \* MON-FRI$/;
const CRON_SELECTED_DAYS_PATTERN = /^0 (\d{1,2}) (\d{1,2}) \* \* ([A-Z,]+)$/;

interface SchedulerFormStateResult {
  form: ScheduleFormState;
  effectiveTargetId: string;
  isTimeValid: boolean;
  isCronValid: boolean;
  isFormValid: boolean;
  editingScheduleId: string | null;
  setTargetId: (targetId: string) => void;
  setMode: (mode: SchedulerMode) => void;
  setFrequency: (frequency: SchedulerFrequency) => void;
  setTime: (value: string) => void;
  setCronExpression: (value: string) => void;
  setLimitInput: (value: string) => void;
  setTargetType: (targetType: SchedulerTargetType) => void;
  setEnabled: (enabled: boolean) => void;
  toggleDay: (day: number) => void;
  prepareCreateForTarget: (targetType: SchedulerTargetType, targetId: string) => void;
  startEditing: (schedule: SchedulerSchedule) => void;
  reset: () => void;
  buildCreateRequest: () => CreateScheduleRequest | null;
  buildUpdateRequest: () => { scheduleId: string; request: UpdateScheduleRequest } | null;
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
    enabled: true,
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

function collectTaskIds(goals: SchedulerGoal[], standaloneTasks: SchedulerTask[]): string[] {
  const goalTaskIds = goals.flatMap((goal) => goal.tasks.map((task) => task.id));
  const standaloneTaskIds = standaloneTasks.map((task) => task.id);
  return [...goalTaskIds, ...standaloneTaskIds];
}

function resolveEffectiveTargetId(
  goals: SchedulerGoal[],
  standaloneTasks: SchedulerTask[],
  form: ScheduleFormState,
): string {
  const targetIds = form.targetType === 'GOAL'
    ? goals.map((goal) => goal.id)
    : collectTaskIds(goals, standaloneTasks);

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

function createUpdateRequest(form: ScheduleFormState, targetId: string, maxExecutions: number): UpdateScheduleRequest {
  const baseRequest = form.mode === 'advanced'
    ? buildAdvancedCreateRequest(form, targetId, maxExecutions)
    : buildSimpleCreateRequest(form, targetId, maxExecutions);
  return {
    ...baseRequest,
    enabled: form.enabled,
  };
}

function formatTime(hour: string, minute: string): string {
  return `${hour.padStart(2, '0')}:${minute.padStart(2, '0')}`;
}

function cronDaysToFrequency(daysPart: string): { frequency: SchedulerFrequency; days: number[] } | null {
  const dayMap: Record<string, number> = {
    MON: 1,
    TUE: 2,
    WED: 3,
    THU: 4,
    FRI: 5,
    SAT: 6,
    SUN: 7,
  };

  const segments = daysPart.split(',');
  const days: number[] = [];
  for (const segment of segments) {
    const normalizedSegment = segment.trim().toUpperCase();
    const day = dayMap[normalizedSegment];
    if (day == null) {
      return null;
    }
    days.push(day);
  }

  const sortedDays = [...days].sort((left, right) => left - right);
  return {
    frequency: sortedDays.length === 1 ? 'weekly' : 'custom',
    days: sortedDays,
  };
}

function parseScheduleToFormState(schedule: SchedulerSchedule): ScheduleFormState {
  const dailyMatch = CRON_DAILY_PATTERN.exec(schedule.cronExpression);
  if (dailyMatch != null) {
    return {
      targetType: schedule.type,
      targetId: schedule.targetId,
      mode: 'simple',
      frequency: 'daily',
      days: [1],
      time: formatTime(dailyMatch[2], dailyMatch[1]),
      cronExpression: schedule.cronExpression,
      limitInput: schedule.maxExecutions > 0 ? String(schedule.maxExecutions) : '0',
      enabled: schedule.enabled,
    };
  }

  const weekdaysMatch = CRON_WEEKDAYS_PATTERN.exec(schedule.cronExpression);
  if (weekdaysMatch != null) {
    return {
      targetType: schedule.type,
      targetId: schedule.targetId,
      mode: 'simple',
      frequency: 'weekdays',
      days: [1, 2, 3, 4, 5],
      time: formatTime(weekdaysMatch[2], weekdaysMatch[1]),
      cronExpression: schedule.cronExpression,
      limitInput: schedule.maxExecutions > 0 ? String(schedule.maxExecutions) : '0',
      enabled: schedule.enabled,
    };
  }

  const selectedDaysMatch = CRON_SELECTED_DAYS_PATTERN.exec(schedule.cronExpression);
  if (selectedDaysMatch != null) {
    const parsedDays = cronDaysToFrequency(selectedDaysMatch[3]);
    if (parsedDays != null) {
      return {
        targetType: schedule.type,
        targetId: schedule.targetId,
        mode: 'simple',
        frequency: parsedDays.frequency,
        days: parsedDays.days,
        time: formatTime(selectedDaysMatch[2], selectedDaysMatch[1]),
        cronExpression: schedule.cronExpression,
        limitInput: schedule.maxExecutions > 0 ? String(schedule.maxExecutions) : '0',
        enabled: schedule.enabled,
      };
    }
  }

  return {
    targetType: schedule.type,
    targetId: schedule.targetId,
    mode: 'advanced',
    frequency: 'daily',
    days: [1],
    time: '09:00',
    cronExpression: schedule.cronExpression,
    limitInput: schedule.maxExecutions > 0 ? String(schedule.maxExecutions) : '0',
    enabled: schedule.enabled,
  };
}

export function useSchedulerForm(
  goals: SchedulerGoal[],
  standaloneTasks: SchedulerTask[],
): SchedulerFormStateResult {
  const [form, setForm] = useState<ScheduleFormState>(buildInitialFormState);
  const [editingScheduleId, setEditingScheduleId] = useState<string | null>(null);

  const effectiveTargetId = useMemo(
    () => resolveEffectiveTargetId(goals, standaloneTasks, form),
    [form, goals, standaloneTasks],
  );
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
    editingScheduleId,
    setTargetId: (targetId) => setForm((current) => ({ ...current, targetId })),
    setMode: (mode) => setForm((current) => ({ ...current, mode })),
    setFrequency: (frequency) => setForm((current) => ({ ...current, frequency })),
    setTime: (time) => setForm((current) => ({ ...current, time })),
    setCronExpression: (cronExpression) => setForm((current) => ({ ...current, cronExpression })),
    setLimitInput: (limitInput) => setForm((current) => ({ ...current, limitInput })),
    setTargetType: (targetType) => setForm((current) => ({ ...current, targetType, targetId: '' })),
    setEnabled: (enabled) => setForm((current) => ({ ...current, enabled })),
    toggleDay: (day) => setForm((current) => ({ ...current, days: toggleDaySelection(current.days, day) })),
    prepareCreateForTarget: (targetType, targetId) => {
      setEditingScheduleId(null);
      setForm({
        ...buildInitialFormState(),
        targetType,
        targetId,
      });
    },
    startEditing: (schedule) => {
      setEditingScheduleId(schedule.id);
      setForm(parseScheduleToFormState(schedule));
    },
    reset: () => {
      setEditingScheduleId(null);
      setForm(buildInitialFormState());
    },
    buildCreateRequest: () => {
      if (!isFormValid || parsedLimit == null) {
        return null;
      }
      if (form.mode === 'advanced') {
        return buildAdvancedCreateRequest(form, effectiveTargetId, parsedLimit);
      }
      return buildSimpleCreateRequest(form, effectiveTargetId, parsedLimit);
    },
    buildUpdateRequest: () => {
      if (editingScheduleId == null || !isFormValid || parsedLimit == null) {
        return null;
      }
      return {
        scheduleId: editingScheduleId,
        request: createUpdateRequest(form, effectiveTargetId, parsedLimit),
      };
    },
  };
}
