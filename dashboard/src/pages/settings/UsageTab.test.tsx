import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import UsageTab from './UsageTab';

vi.mock('../../hooks/useSettings', () => ({
  useUpdateSessionRetention: () => ({ mutateAsync: vi.fn(), isPending: false }),
  useUpdateUsage: () => ({ mutateAsync: vi.fn(), isPending: false }),
}));

describe('UsageTab', () => {
  it('describes plan retention as active runtime plan mode only', () => {
    const html = renderToStaticMarkup(
      <UsageTab
        config={{ enabled: true }}
        sessionRetention={{
          enabled: true,
          maxAge: 'P30D',
          cleanupInterval: 'PT24H',
          protectActiveSessions: true,
          protectSessionsWithPlans: true,
          protectSessionsWithDelayedActions: true,
        }}
      />,
    );

    expect(html).toContain('Protect active plan mode sessions');
    expect(html).toContain('current runtime');
    expect(html).not.toContain('collecting, ready, approved, or executing plans');
  });
});
