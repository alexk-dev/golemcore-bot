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
      plans: [
        {
          id: 'plan-1',
          title: 'Special tier review',
          status: 'READY',
          modelTier: 'special3',
          createdAt: null,
          updatedAt: null,
          stepCount: 4,
          completedStepCount: 1,
          failedStepCount: 0,
          active: true,
        },
      ],
    },
    isLoading: false,
    isError: false,
    refetch: vi.fn(),
  }),
  useEnablePlanMode: () => ({ mutate: vi.fn(), isPending: false }),
  useDisablePlanMode: () => ({ mutate: vi.fn(), isPending: false }),
  useDonePlanMode: () => ({ mutate: vi.fn(), isPending: false }),
  useApprovePlan: () => ({ mutate: vi.fn(), isPending: false }),
  useCancelPlan: () => ({ mutate: vi.fn(), isPending: false }),
  useResumePlan: () => ({ mutate: vi.fn(), isPending: false }),
  usePlanActionsPending: () => false,
}));

describe('PlanControlPanel', () => {
  it('renders a tier override selector and shows plan tier labels', () => {
    const html = renderToStaticMarkup(<PlanControlPanel chatSessionId="chat-1" />);

    expect(html).toContain('Plan tier override');
    expect(html).toContain('Default routing');
    expect(html).toContain('Special 5');
    expect(html).toContain('tier Special 3');
  });
});
