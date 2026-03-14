import type { SchedulerTargetType } from '../../api/scheduler';

export const GOALS_PAGE_PATH = '/goals';
export const SCHEDULER_PAGE_PATH = '/scheduler';

export function getGoalAnchorId(goalId: string): string {
  return `goal-${goalId}`;
}

export function getTaskAnchorId(taskId: string): string {
  return `task-${taskId}`;
}

export function getGoalHref(goalId: string): string {
  return `${GOALS_PAGE_PATH}#${getGoalAnchorId(goalId)}`;
}

export function getTaskHref(taskId: string): string {
  return `${GOALS_PAGE_PATH}#${getTaskAnchorId(taskId)}`;
}

export function getSchedulerPrefillHref(targetType: SchedulerTargetType, targetId: string): string {
  const params = new URLSearchParams({
    targetType,
    targetId,
  });
  return `${SCHEDULER_PAGE_PATH}?${params.toString()}`;
}
