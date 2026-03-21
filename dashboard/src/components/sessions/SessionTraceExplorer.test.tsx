import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';

import type { SessionTrace, SessionTraceSummary } from '../../api/sessions';
import { SessionTraceExplorer } from './SessionTraceExplorer';

const trace: SessionTrace = {
  sessionId: 'session-1',
  storageStats: {
    compressedSnapshotBytes: 512,
    uncompressedSnapshotBytes: 1024,
    evictedSnapshots: 0,
    evictedTraces: 0,
    truncatedTraces: 0,
  },
  traces: [
    {
      traceId: 'trace-1',
      rootSpanId: 'span-root',
      traceName: 'web.message',
      startedAt: '2026-03-20T10:00:00Z',
      endedAt: '2026-03-20T10:00:02Z',
      truncated: false,
      compressedSnapshotBytes: 512,
      uncompressedSnapshotBytes: 1024,
      spans: [
        {
          spanId: 'span-root',
          parentSpanId: null,
          name: 'web.request',
          kind: 'INGRESS',
          statusCode: 'OK',
          statusMessage: null,
          startedAt: '2026-03-20T10:00:00Z',
          endedAt: '2026-03-20T10:00:02Z',
          durationMs: 2000,
          attributes: { channel: 'web' },
          events: [],
          snapshots: [
            {
              snapshotId: 'snap-1',
              role: 'request',
              contentType: 'application/json',
              encoding: 'zstd',
              originalSize: 30,
              compressedSize: 16,
              truncated: false,
              payloadAvailable: true,
              payloadPreview: '{"message":"hello"}',
              payloadPreviewTruncated: false,
            },
          ],
        },
        {
          spanId: 'span-llm',
          parentSpanId: 'span-root',
          name: 'llm.chat',
          kind: 'LLM',
          statusCode: 'OK',
          statusMessage: null,
          startedAt: '2026-03-20T10:00:00.200Z',
          endedAt: '2026-03-20T10:00:01.500Z',
          durationMs: 1300,
          attributes: { attempt: 1 },
          events: [],
          snapshots: [],
        },
      ],
    },
  ],
};

const summary: SessionTraceSummary = {
  sessionId: 'session-1',
  traceCount: 1,
  spanCount: 2,
  snapshotCount: 1,
  storageStats: {
    compressedSnapshotBytes: 512,
    uncompressedSnapshotBytes: 1024,
    evictedSnapshots: 0,
    evictedTraces: 0,
    truncatedTraces: 0,
  },
  traces: [
    {
      traceId: 'trace-1',
      rootSpanId: 'span-root',
      traceName: 'web.message',
      rootKind: 'INGRESS',
      rootStatusCode: 'OK',
      startedAt: '2026-03-20T10:00:00Z',
      endedAt: '2026-03-20T10:00:02Z',
      durationMs: 2000,
      spanCount: 2,
      snapshotCount: 1,
      truncated: false,
    },
  ],
};

describe('SessionTraceExplorer', () => {
  it('renders trace overview, span tree, and snapshot preview', () => {
    const html = renderToStaticMarkup(
      <SessionTraceExplorer
        summary={summary}
        trace={trace}
        isLoadingSummary={false}
        isLoadingTrace={false}
        errorMessage={null}
        preferredTraceId={null}
        onLoadTrace={vi.fn()}
        onExport={vi.fn()}
      />,
    );

    expect(html).toContain('Trace Explorer');
    expect(html).toContain('web.message');
    expect(html).toContain('llm.chat');
    expect(html).toContain('Payload preview');
    expect(html).toContain('request');
  });

  it('renders summary-only state before full trace details are loaded', () => {
    const html = renderToStaticMarkup(
      <SessionTraceExplorer
        summary={summary}
        trace={null}
        isLoadingSummary={false}
        isLoadingTrace={false}
        errorMessage={null}
        preferredTraceId={null}
        onLoadTrace={vi.fn()}
        onExport={vi.fn()}
      />,
    );

    expect(html).toContain('Trace summary');
    expect(html).toContain('Load details');
    expect(html).toContain('web.message');
  });

  it('renders a visible error state when trace loading fails', () => {
    const html = renderToStaticMarkup(
      <SessionTraceExplorer
        summary={null}
        trace={null}
        isLoadingSummary={false}
        isLoadingTrace={false}
        errorMessage="Failed to load trace details: boom"
        preferredTraceId={null}
        onLoadTrace={vi.fn()}
        onExport={vi.fn()}
      />,
    );

    expect(html).toContain('Failed to load trace details: boom');
  });
});
