import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';

import { SelfEvolvingVerdictPanel } from './SelfEvolvingVerdictPanel';

describe('SelfEvolvingVerdictPanel', () => {
  it('renders selected run context when verdict is not available yet', () => {
    const html = renderToStaticMarkup(
      <SelfEvolvingVerdictPanel
        run={{
          id: 'run-2',
          golemId: 'golem-2',
          sessionId: 'session-2',
          traceId: 'trace-2',
          artifactBundleId: 'bundle-2',
          artifactBundleStatus: 'candidate',
          status: 'completed',
          startedAt: '2026-04-04T13:00:00Z',
          completedAt: '2026-04-04T13:02:00Z',
          verdict: null,
        }}
        isLoading={false}
      />,
    );

    expect(html).toContain('run-2');
    expect(html).toContain('trace-2');
    expect(html).toContain('bundle-2');
    expect(html).toContain('The judge has not evaluated this run yet');
    expect(html).not.toContain('Select a run from the table');
  });
});
