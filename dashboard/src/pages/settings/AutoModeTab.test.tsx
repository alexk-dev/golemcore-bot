import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import type { AutoModeConfig } from '../../api/settings';
import AutoModeTab from './AutoModeTab';

vi.mock('../../hooks/useSettings', () => ({
  useUpdateAuto: () => ({
    mutateAsync: vi.fn(() => Promise.resolve()),
    isPending: false,
  }),
}));

const config: AutoModeConfig = {
  enabled: true,
  tickIntervalSeconds: 300,
  taskTimeLimitMinutes: 10,
  autoStart: true,
  maxGoals: 3,
  modelTier: null,
  reflectionEnabled: true,
  reflectionFailureThreshold: 2,
  reflectionModelTier: null,
  reflectionTierPriority: false,
  notifyMilestones: true,
};

describe('AutoModeTab', () => {
  it('renders reflection controls with special tier options', () => {
    const html = renderToStaticMarkup(<AutoModeTab config={config} />);

    expect(html).toContain('Reflection tier');
    expect(html).toContain('Reflection after failures');
    expect(html).toContain('Prefer reflection tier over active skill tier');
    expect(html).toContain('Special 5');
    expect(html).toContain('Use task tier');
  });
});
