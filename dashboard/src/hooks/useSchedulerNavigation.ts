import type { GoalTask } from '../api/goals';
import type {
  SchedulerGoal,
  SchedulerSchedule,
  SchedulerScheduledTask,
} from '../api/scheduler';
import {
  getGoalHref,
  getScheduledTaskHref,
  getTaskHref,
} from '../components/scheduler/automationLinks';

interface UseSchedulerNavigationResult {
  resolveGoalHref: (goalId: string) => string | null;
  resolveTaskHref: (taskId: string) => string | null;
  resolveScheduledTaskHref: (scheduledTaskId: string) => string | null;
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
  scheduledTasks: SchedulerScheduledTask[],
  openScheduleEditor: (schedule: SchedulerSchedule) => void,
): UseSchedulerNavigationResult {
  const resolveGoalHref = (goalId: string): string | null => (
    goals.some((goal) => goal.id === goalId) ? getGoalHref(goalId) : null
  );

  const resolveTaskHref = (taskId: string): string | null => (
    hasTask(taskId, goals, standaloneTasks) ? getTaskHref(taskId) : null
  );

  const resolveScheduledTaskHref = (scheduledTaskId: string): string | null => (
    scheduledTasks.some((task) => task.id === scheduledTaskId)
      ? getScheduledTaskHref(scheduledTaskId)
      : null
  );

  const resolveScheduleTargetHref = (schedule: SchedulerSchedule): string | null => {
    if (schedule.type === 'GOAL') {
      return resolveGoalHref(schedule.targetId);
    }
    if (schedule.type === 'TASK') {
      return resolveTaskHref(schedule.targetId);
    }
    return resolveScheduledTaskHref(schedule.targetId);
  };

  return {
    resolveGoalHref,
    resolveTaskHref,
    resolveScheduledTaskHref,
    resolveScheduleTargetHref,
    openScheduleEditor,
  };
}
