import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';

import SelfEvolvingTab from './SelfEvolvingTab';

vi.mock('../../hooks/useSettings', () => ({
  useUpdateRuntimeConfig: () => ({
    mutateAsync: vi.fn(() => Promise.resolve()),
    isPending: false,
  }),
}));

const config = {
  enabled: true,
  tracePayloadOverride: true,
  capture: {
    llm: 'full',
    tool: 'full',
    context: 'full',
    skill: 'full',
    tier: 'full',
    infra: 'meta_only',
  },
  judge: {
    enabled: true,
    primaryTier: 'standard',
    tiebreakerTier: 'premium',
    evolutionTier: 'premium',
    requireEvidenceAnchors: true,
    uncertaintyThreshold: 0.22,
  },
  evolution: {
    enabled: true,
    modes: ['fix', 'derive', 'tune'],
    artifactTypes: ['skill', 'prompt', 'routing_policy'],
  },
  promotion: {
    mode: 'approval_gate' as const,
    allowAutoAccept: true,
    shadowRequired: true,
    canaryRequired: true,
    hiveApprovalPreferred: true,
  },
  benchmark: {
    enabled: true,
    harvestProductionRuns: true,
    autoCreateRegressionCases: true,
  },
  hive: {
    publishInspectionProjection: true,
    readonlyInspection: true,
  },
};

describe('SelfEvolvingTab', () => {
  it('renders judge tier selectors and promotion controls', () => {
    const html = renderToStaticMarkup(<SelfEvolvingTab config={config} onSave={vi.fn(() => Promise.resolve())} />);

    expect(html).toContain('Primary judge tier');
    expect(html).toContain('Tiebreaker tier');
    expect(html).toContain('Promotion mode');
    expect(html).toContain('Trace payload override');
  });
});
