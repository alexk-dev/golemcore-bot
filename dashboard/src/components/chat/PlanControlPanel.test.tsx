import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import PlanControlPanel from './PlanControlPanel';

vi.mock('../../hooks/usePlans', () => ({
  usePlanControlState: () => ({
    data: {
      featureEnabled: true,
      sessionId: 'chat-1',
      planModeActive: false,
      activePlanId: null,
      plans: [],
    },
    isLoading: false,
    isError: false,
    refetch: vi.fn(),
  }),
  useEnablePlanMode: () => ({ mutate: vi.fn(), isPending: false }),
  useDisablePlanMode: () => ({ mutate: vi.fn(), isPending: false }),
  useDonePlanMode: () => ({ mutate: vi.fn(), isPending: false }),
  usePlanActionsPending: () => false,
}));

describe('PlanControlPanel', () => {
  it('renders ephemeral plan mode controls without settings', () => {
    const html = renderToStaticMarkup(<PlanControlPanel chatSessionId="chat-1" />);

    expect(html).not.toContain('Plan tier override');
    expect(html).not.toContain('Default routing');
    expect(html).not.toContain('Special 5');
    expect(html).toContain('Plan ON');
    expect(html).not.toContain('Approve');
  });
});
