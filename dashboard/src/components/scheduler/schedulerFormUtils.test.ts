import { describe, expect, it } from 'vitest';
import type { GoalTask } from '../../api/goals';
import type { SchedulerSchedule, SchedulerScheduledTask } from '../../api/scheduler';
import {
  buildGoalOptions,
  buildScheduledTaskOptions,
  buildTaskOptions,
  formatLimit,
  isScheduleUnlimited,
  isValidTimeInput,
  normalizeTimeInput,
  parseLimitInput,
} from './schedulerFormUtils';

describe('schedulerFormUtils', () => {
  it('parses limit input conservatively', () => {
    expect(parseLimitInput(' 12 ')).toBe(12);
    expect(parseLimitInput('0')).toBe(0);
    expect(parseLimitInput('-1')).toBeNull();
    expect(parseLimitInput('abc')).toBeNull();
    expect(parseLimitInput('')).toBeNull();
  });

  it('validates and normalizes both HHmm and HH:mm time inputs', () => {
    expect(isValidTimeInput('0930')).toBe(true);
    expect(isValidTimeInput('9:30')).toBe(true);
    expect(isValidTimeInput('24:00')).toBe(false);
    expect(isValidTimeInput('2360')).toBe(false);
    expect(isValidTimeInput('930')).toBe(false);

    expect(normalizeTimeInput('0930')).toBe('09:30');
    expect(normalizeTimeInput(' 9:30 ')).toBe('9:30');
  });

  it('builds target options for goals, goal tasks, and standalone tasks', () => {
    const standaloneTask: GoalTask = {
      id: 'task-2',
      goalId: null,
      title: 'Review alerts',
      description: null,
      prompt: null,
      reflectionModelTier: null,
      reflectionTierPriority: false,
      status: 'PENDING',
      order: 1,
      standalone: true,
    };

    expect(buildGoalOptions([
      { id: 'goal-1', title: 'Release v2', status: 'ACTIVE' },
    ])).toEqual([
      { id: 'goal-1', label: 'Release v2 (ACTIVE)' },
    ]);

    expect(buildTaskOptions([
      {
        title: 'Release v2',
        tasks: [{ id: 'task-1', title: 'Write release notes' }],
      },
    ], [standaloneTask])).toEqual([
      { id: 'task-1', label: 'Write release notes — Release v2' },
      { id: 'task-2', label: 'Review alerts — Standalone task' },
    ]);

    const scheduledTask: SchedulerScheduledTask = {
      id: 'scheduled-1',
      title: 'Nightly indexing',
      description: null,
      prompt: null,
      executionMode: 'SHELL_COMMAND',
      shellCommand: 'npm run index',
      shellWorkingDirectory: '/srv/app',
      reflectionModelTier: null,
      reflectionTierPriority: false,
      legacySourceType: null,
      legacySourceId: null,
    };

    expect(buildScheduledTaskOptions([scheduledTask])).toEqual([
      { id: 'scheduled-1', label: 'Nightly indexing (SHELL_COMMAND)' },
    ]);
  });

  it('formats unlimited schedules consistently', () => {
    const schedule: SchedulerSchedule = {
      id: 'sched-1',
      type: 'GOAL',
      targetId: 'goal-1',
      targetLabel: 'Release v2',
      cronExpression: '0 0 9 * * *',
      enabled: true,
      clearContextBeforeRun: false,
      report: null,
      maxExecutions: 0,
      executionCount: 0,
      createdAt: null,
      updatedAt: null,
      lastExecutedAt: null,
      nextExecutionAt: null,
    };

    expect(formatLimit(-1)).toBe('Unlimited');
    expect(isScheduleUnlimited(schedule)).toBe(true);
  });
});
