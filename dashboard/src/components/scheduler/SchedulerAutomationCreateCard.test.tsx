import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import type { Goal } from '../../api/goals';
import { SchedulerAutomationCreateCard } from './SchedulerAutomationCreateCard';

const goals: Goal[] = [
  {
    id: 'goal-1',
    title: 'Ship special tier support',
    description: null,
    prompt: null,
    reflectionModelTier: 'special2',
    reflectionTierPriority: true,
    status: 'ACTIVE',
    completedTasks: 0,
    totalTasks: 0,
    tasks: [],
  },
];

describe('SchedulerAutomationCreateCard', () => {
  it('renders reflection tier controls for new goals and tasks', () => {
    const html = renderToStaticMarkup(
      <SchedulerAutomationCreateCard
        featureEnabled
        goals={goals}
        busy={false}
        onCreateGoal={vi.fn()}
        onCreateTask={vi.fn()}
      />,
    );

    expect(html).toContain('Reflection tier');
    expect(html).toContain('Reflection tier has priority');
    expect(html).toContain('Use default reflection model');
    expect(html).toContain('Special 4');
  });
});
