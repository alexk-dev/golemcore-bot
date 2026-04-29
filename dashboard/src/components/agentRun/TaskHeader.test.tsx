import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import TaskHeader from './TaskHeader';

describe('TaskHeader', () => {
  it('renders title, status pill, started time and duration', () => {
    const html = renderToStaticMarkup(
      <TaskHeader
        title="Optimizer tuning"
        status="waiting_retry"
        startedAt="2026-04-29T10:31:00.000Z"
        durationMs={765_000}
        stepCount={18}
      />,
    );
    expect(html).toContain('Optimizer tuning');
    expect(html).toContain('Waiting for retry');
    expect(html).toContain('Started');
    expect(html).toContain('12m 45s');
    expect(html).toContain('18 steps');
  });

  it('hides edit button when no onTitleChange handler', () => {
    const html = renderToStaticMarkup(
      <TaskHeader
        title="Run"
        status="running"
        startedAt="2026-04-29T10:00:00.000Z"
        durationMs={1000}
        stepCount={1}
      />,
    );
    expect(html).not.toContain('Edit task title');
  });

  it('exposes edit button when onTitleChange provided', () => {
    const html = renderToStaticMarkup(
      <TaskHeader
        title="Run"
        status="running"
        startedAt="2026-04-29T10:00:00.000Z"
        durationMs={1000}
        stepCount={1}
        onTitleChange={() => undefined}
      />,
    );
    expect(html).toContain('Edit task title');
  });
});
