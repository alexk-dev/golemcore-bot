import type { AgentRunStatus, PlanStepStatus, ToolCallStatus } from './types';

export function formatDuration(durationMs: number): string {
  if (durationMs < 0) {
    return '0s';
  }
  if (durationMs < 1000) {
    return `${Math.round(durationMs)}ms`;
  }
  const totalSeconds = Math.floor(durationMs / 1000);
  if (totalSeconds < 60) {
    const seconds = (durationMs / 1000).toFixed(1).replace(/\.0$/, '');
    return `${seconds}s`;
  }
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return `${minutes}m ${String(seconds).padStart(2, '0')}s`;
}

export function formatTimeOfDay(iso: string | null | undefined, locale = 'en-US'): string {
  if (iso == null) {
    return '';
  }
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) {
    return iso;
  }
  return date.toLocaleTimeString(locale, { hour: '2-digit', minute: '2-digit' });
}

export const PLAN_STATUS_LABELS: Record<PlanStepStatus, string> = {
  pending: 'Pending',
  running: 'In progress',
  completed: 'Completed',
  failed: 'Failed',
  skipped: 'Skipped',
  waiting_approval: 'Waiting approval',
};

export const TOOL_STATUS_LABELS: Record<ToolCallStatus, string> = {
  pending: 'Pending',
  running: 'Running',
  success: 'Success',
  failed: 'Failed',
  skipped: 'Skipped',
  cancelled: 'Cancelled',
};

export const RUN_STATUS_LABELS: Record<AgentRunStatus, string> = {
  idle: 'Idle',
  running: 'Running',
  waiting_approval: 'Needs approval',
  waiting_retry: 'Waiting for retry',
  paused: 'Paused',
  completed: 'Completed',
  failed: 'Failed',
  cancelled: 'Cancelled',
};

export function formatCountdown(totalSeconds: number): string {
  const safe = Math.max(0, Math.floor(totalSeconds));
  const minutes = Math.floor(safe / 60);
  const seconds = safe % 60;
  return `${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}`;
}
