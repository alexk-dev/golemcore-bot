/* @vitest-environment jsdom */

import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Goal, GoalTask } from '../../api/goals';
import { useContextPanelStore } from '../../store/contextPanelStore';

vi.mock('./PlanControlPanel', () => ({
  default: () => <div data-testid="plan-control-panel" />,
}));

import ContextPanel from './ContextPanel';

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

function createTask(overrides: Partial<GoalTask>): GoalTask {
  return {
    id: 'task-1',
    goalId: 'goal-1',
    title: 'Task',
    description: null,
    prompt: null,
    reflectionModelTier: null,
    reflectionTierPriority: false,
    status: 'PENDING',
    order: 1,
    standalone: false,
    ...overrides,
  };
}

function createGoal(overrides: Partial<Goal>): Goal {
  return {
    id: 'goal-1',
    title: 'Goal',
    description: null,
    prompt: null,
    reflectionModelTier: null,
    reflectionTierPriority: false,
    status: 'ACTIVE',
    completedTasks: 0,
    totalTasks: 1,
    tasks: [],
    ...overrides,
  };
}

describe('ContextPanel', () => {
  let root: Root | null = null;

  afterEach(() => {
    if (root != null) {
      act(() => {
        root?.unmount();
      });
      root = null;
    }
    document.body.innerHTML = '';
  });

  beforeEach(() => {
    useContextPanelStore.setState({
      panelOpen: true,
      mobileDrawerOpen: false,
      goals: [],
      standaloneTasks: [],
      goalsFeatureEnabled: false,
      autoModeEnabled: false,
    });
  });

  it('renders session goals and standalone tasks in chat context', () => {
    const goalTask = createTask({ id: 'task-goal-1', title: 'Prepare changelog' });
    const standaloneTask = createTask({
      id: 'task-standalone-1',
      goalId: null,
      title: 'Investigate flaky test',
      standalone: true,
      status: 'IN_PROGRESS',
    });

    useContextPanelStore.getState().setGoals(
      [createGoal({
        id: 'goal-release',
        title: 'Release v2',
        completedTasks: 0,
        totalTasks: 1,
        tasks: [goalTask],
      })],
      [standaloneTask],
      true,
      false,
    );

    const container = document.createElement('div');
    document.body.appendChild(container);
    root = createRoot(container);

    act(() => {
      root?.render(
        <ContextPanel
          tier="balanced"
          tierForce={false}
          chatSessionId="session-123"
          onTierChange={vi.fn()}
          onForceChange={vi.fn()}
          forceOpen
        />,
      );
    });

    expect(document.body.textContent).toContain('GOALS & TASKS');
    expect(document.body.textContent).toContain('Release v2');
    expect(document.body.textContent).toContain('Prepare changelog');
    expect(document.body.textContent).toContain('Standalone tasks');
    expect(document.body.textContent).toContain('Investigate flaky test');
    expect(document.body.textContent).not.toContain('goal-release');
    expect(document.body.textContent).not.toContain('task-goal-1');
    expect(document.body.textContent).not.toContain('task-standalone-1');
  });
});
