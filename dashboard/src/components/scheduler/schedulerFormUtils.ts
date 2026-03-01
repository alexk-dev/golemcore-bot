import type { SchedulerSchedule } from '../../api/scheduler';

const HHMM_COLON_PATTERN = /^(\d{1,2}):(\d{1,2})$/;

export function parseLimitInput(value: string): number | null {
  const trimmed = value.trim();
  if (trimmed.length === 0) {
    return null;
  }

  const parsed = Number.parseInt(trimmed, 10);
  if (Number.isNaN(parsed) || parsed < 0) {
    return null;
  }

  return parsed;
}

export function isValidTimeInput(value: string): boolean {
  const trimmed = value.trim();
  if (/^\d{4}$/.test(trimmed)) {
    const hour = Number.parseInt(trimmed.slice(0, 2), 10);
    const minute = Number.parseInt(trimmed.slice(2, 4), 10);
    return hour >= 0 && hour <= 23 && minute >= 0 && minute <= 59;
  }

  const match = HHMM_COLON_PATTERN.exec(trimmed);
  if (match == null) {
    return false;
  }

  const hour = Number.parseInt(match[1], 10);
  const minute = Number.parseInt(match[2], 10);
  return hour >= 0 && hour <= 23 && minute >= 0 && minute <= 59;
}

export function normalizeTimeInput(value: string): string {
  const trimmed = value.trim();
  if (/^\d{4}$/.test(trimmed)) {
    return `${trimmed.slice(0, 2)}:${trimmed.slice(2, 4)}`;
  }
  return trimmed;
}

export function formatLimit(maxExecutions: number): string {
  return maxExecutions > 0 ? String(maxExecutions) : 'Unlimited';
}

export function formatNextExecution(value: string | null): string {
  if (value == null) {
    return '—';
  }
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return value;
  }
  return `${date.toLocaleString()} UTC`;
}

export interface SchedulerTargetOption {
  id: string;
  label: string;
}

export function buildGoalOptions(goals: Array<{ id: string; title: string; status: string }>): SchedulerTargetOption[] {
  return goals.map((goal) => ({
    id: goal.id,
    label: `${goal.title} (${goal.status})`,
  }));
}

export function buildTaskOptions(goals: Array<{
  title: string;
  tasks: Array<{ id: string; title: string }>;
}>): SchedulerTargetOption[] {
  return goals.flatMap((goal) => goal.tasks.map((task) => ({
    id: task.id,
    label: `${task.title} — ${goal.title}`,
  })));
}

export function isScheduleUnlimited(schedule: SchedulerSchedule): boolean {
  return schedule.maxExecutions <= 0;
}
