import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import type { PlanConfig } from '../../api/settingsTypes';
import PlanModeTab from './PlanModeTab';

vi.mock('../../hooks/useSettings', () => ({
  useUpdatePlan: () => ({
    mutateAsync: vi.fn(() => Promise.resolve()),
    isPending: false,
  }),
}));

const config: PlanConfig = {
  modelTier: null,
};

describe('PlanModeTab', () => {
  it('renders only the plan tier override setting', () => {
    const html = renderToStaticMarkup(<PlanModeTab config={config} />);

    expect(html).toContain('Plan tier override');
    expect(html).toContain('Default routing');
    expect(html).toContain('Special 5');
    expect(html).not.toContain('Enable Plan Mode');
    expect(html).not.toContain('Max Active Plans');
    expect(html).not.toContain('Stop execution on first failed step');
  });
});
