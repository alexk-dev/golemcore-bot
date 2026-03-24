import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';

import type { TracingConfig } from '../../api/settings';
import TracingTab from './TracingTab';

vi.mock('../../hooks/useSettings', () => ({
  useUpdateTracing: () => ({
    mutateAsync: vi.fn(() => Promise.resolve()),
    isPending: false,
  }),
}));

const config: TracingConfig = {
  enabled: true,
  payloadSnapshotsEnabled: false,
  sessionTraceBudgetMb: 128,
  maxSnapshotSizeKb: 256,
  maxSnapshotsPerSpan: 10,
  maxTracesPerSession: 100,
  captureInboundPayloads: true,
  captureOutboundPayloads: true,
  captureToolPayloads: true,
  captureLlmPayloads: true,
};

describe('TracingTab', () => {
  it('renders tracing controls and payload capture toggles', () => {
    const html = renderToStaticMarkup(<TracingTab config={config} />);

    expect(html).toContain('Payload snapshots');
    expect(html).toContain('Session trace budget (MB)');
    expect(html).toContain('Capture inbound payloads');
    expect(html).toContain('Capture outbound payloads');
    expect(html).toContain('Capture tool payloads');
    expect(html).toContain('Capture LLM payloads');
  });

  it('shows placeholders instead of forcing fallback values when nullable limits are unset', () => {
    const html = renderToStaticMarkup(
      <TracingTab
        config={{
          ...config,
          sessionTraceBudgetMb: null,
          maxSnapshotSizeKb: null,
          maxSnapshotsPerSpan: null,
          maxTracesPerSession: null,
        }}
      />,
    );

    expect(html).toContain('placeholder="128"');
    expect(html).toContain('placeholder="256"');
    expect(html).toContain('placeholder="10"');
    expect(html).toContain('placeholder="100"');
  });
});
