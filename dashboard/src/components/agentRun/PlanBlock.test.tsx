import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import PlanBlock from './PlanBlock';
import type { PlanViewModel } from './types';

const plan: PlanViewModel = {
  id: 'plan-1',
  runId: 'run-1',
  version: 1,
  updatedAt: '2026-04-29T10:31:00.000Z',
  steps: [
    { id: 's1', index: 1, title: 'Inspect current optimizer config', status: 'completed', relatedToolCallIds: [] },
    { id: 's2', index: 2, title: 'Update switching logic', status: 'completed', relatedToolCallIds: [] },
    { id: 's3', index: 3, title: 'Run optimizer backtest', status: 'running', relatedToolCallIds: [] },
    { id: 's4', index: 4, title: 'Validate results and compare', status: 'pending', relatedToolCallIds: [] },
    { id: 's5', index: 5, title: 'Save report and summary', status: 'pending', relatedToolCallIds: [] },
  ],
};

describe('PlanBlock', () => {
  it('renders all steps with their status labels', () => {
    const html = renderToStaticMarkup(<PlanBlock plan={plan} currentStepIndex={3} />);
    expect(html).toContain('Inspect current optimizer config');
    expect(html).toContain('Run optimizer backtest');
    expect(html).toContain('In progress');
    expect(html).toContain('5 steps');
  });

  it('shows Approve action only when needsApproval', () => {
    const without = renderToStaticMarkup(<PlanBlock plan={plan} onApprove={() => undefined} />);
    expect(without).not.toContain('Approve');

    const withApproval = renderToStaticMarkup(
      <PlanBlock plan={plan} needsApproval onApprove={() => undefined} />,
    );
    expect(withApproval).toContain('Approve');
  });

  it('shows Pause only when run is active', () => {
    const idle = renderToStaticMarkup(<PlanBlock plan={plan} onPause={() => undefined} />);
    expect(idle).not.toContain('Pause');

    const running = renderToStaticMarkup(
      <PlanBlock plan={plan} isRunActive onPause={() => undefined} />,
    );
    expect(running).toContain('Pause');
  });
});
