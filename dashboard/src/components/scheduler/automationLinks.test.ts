import { describe, expect, it } from 'vitest';
import {
  getGoalAnchorId,
  getGoalHref,
  getSchedulerPrefillHref,
  getTaskAnchorId,
  getTaskHref,
  GOALS_PAGE_PATH,
  SCHEDULER_PAGE_PATH,
} from './automationLinks';

describe('automationLinks', () => {
  it('builds stable goal and task anchor links', () => {
    expect(getGoalAnchorId('goal-42')).toBe('goal-goal-42');
    expect(getGoalHref('goal-42')).toBe(`${GOALS_PAGE_PATH}#goal-goal-42`);
    expect(getTaskAnchorId('task-9')).toBe('task-task-9');
    expect(getTaskHref('task-9')).toBe(`${GOALS_PAGE_PATH}#task-task-9`);
  });

  it('builds scheduler prefill href with encoded params', () => {
    const href = getSchedulerPrefillHref('TASK', 'task 9/alpha');
    const [path, query] = href.split('?');

    expect(path).toBe(SCHEDULER_PAGE_PATH);

    const params = new URLSearchParams(query);
    expect(params.get('targetType')).toBe('TASK');
    expect(params.get('targetId')).toBe('task 9/alpha');
  });
});
