import type { RefObject } from 'react';
import type { GoalTask } from '../api/goals';
import type { SchedulerGoal, SchedulerSchedule } from '../api/scheduler';
import { getGoalHref, getTaskHref } from '../components/scheduler/automationLinks';

interface UseSchedulerNavigationResult {
  resolveGoalHref: (goalId: string) => string | null;
  resolveTaskHref: (taskId: string) => string | null;
  resolveScheduleTargetHref: (schedule: SchedulerSchedule) => string | null;
  openScheduleEditor: (schedule: SchedulerSchedule) => void;
}

function hasTask(taskId: string, goals: SchedulerGoal[], standaloneTasks: GoalTask[]): boolean {
  const hasGoalTask = goals.some((goal) => goal.tasks.some((task) => task.id === taskId));
  const hasStandaloneTask = standaloneTasks.some((task) => task.id === taskId);
  return hasGoalTask || hasStandaloneTask;
}

export function useSchedulerNavigation(
  goals: SchedulerGoal[],
  standaloneTasks: GoalTask[],
  scheduleSectionRef: RefObject<HTMLDivElement>,
  startEditing: (schedule: SchedulerSchedule) => void,
): UseSchedulerNavigationResult {
  const resolveGoalHref = (goalId: string): string | null => (
    goals.some((goal) => goal.id === goalId) ? getGoalHref(goalId) : null
  );

  const resolveTaskHref = (taskId: string): string | null => (
    hasTask(taskId, goals, standaloneTasks) ? getTaskHref(taskId) : null
  );

  const resolveScheduleTargetHref = (schedule: SchedulerSchedule): string | null => (
    schedule.type === 'GOAL' ? resolveGoalHref(schedule.targetId) : resolveTaskHref(schedule.targetId)
  );

  const openScheduleEditor = (schedule: SchedulerSchedule): void => {
    startEditing(schedule);
    scheduleSectionRef.current?.scrollIntoView({ behavior: 'smooth', block: 'start' });
  };

  return {
    resolveGoalHref,
    resolveTaskHref,
    resolveScheduleTargetHref,
    openScheduleEditor,
  };
}
