import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';

import type { MessageInfo, SessionTrace, SessionTraceSummary } from '../../api/sessions';
import { SessionTraceExplorer } from './SessionTraceExplorer';

const messages: MessageInfo[] = [
  {
    id: 'message-user',
    role: 'user',
    content: 'Analyze the trace',
    timestamp: '2026-03-20T10:00:00Z',
    hasToolCalls: false,
    hasVoice: false,
    model: null,
    modelTier: null,
    skill: 'analyst',
    reasoning: null,
    clientMessageId: null,
    attachments: [],
  },
  {
    id: 'message-assistant',
    role: 'assistant',
    content: 'I found the issue.',
    timestamp: '2026-03-20T10:00:02Z',
    hasToolCalls: false,
    hasVoice: false,
    model: 'gpt-5-smart',
    modelTier: 'smart',
    skill: 'executor',
    reasoning: 'high',
    clientMessageId: null,
    attachments: [],
  },
];

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
          attributes: {
            attempt: 1,
            'context.skill.name': 'executor',
            'context.model.tier': 'smart',
            'context.model.id': 'gpt-5-smart',
            'context.model.reasoning': 'high',
            'context.model.source': 'skill',
          },
          events: [
            {
              name: 'request.context',
              timestamp: '2026-03-20T10:00:00.300Z',
              attributes: {
                skill: 'executor',
                tier: 'smart',
                model_id: 'gpt-5-smart',
                source: 'skill',
              },
            },
          ],
          snapshots: [],
        },
        {
          spanId: 'span-tool',
          parentSpanId: 'span-root',
          name: 'tool.run',
          kind: 'TOOL',
          statusCode: 'OK',
          statusMessage: null,
          startedAt: '2026-03-20T10:00:01.500Z',
          endedAt: '2026-03-20T10:00:01.700Z',
          durationMs: 200,
          attributes: {},
          events: [],
          snapshots: [
            {
              snapshotId: 'snap-2',
              role: 'result',
              contentType: 'application/json',
              encoding: 'zstd',
              originalSize: 45,
              compressedSize: 20,
              truncated: false,
              payloadAvailable: true,
              payloadPreview: '{"tool":"done"}',
              payloadPreviewTruncated: false,
            },
          ],
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
        messages={messages}
        isLoadingSummary={false}
        isLoadingTrace={false}
        errorMessage={null}
        onLoadTrace={vi.fn()}
        onExport={vi.fn()}
      />,
    );

    expect(html).toContain('Conversation + trace');
    expect(html).toContain('Analyze the trace');
    expect(html).toContain('I found the issue.');
    expect(html).toContain('llm.chat');
    expect(html).toContain('Trace meta');
    expect(html).toContain('Payload inspect');
    expect(html).toContain('skill executor');
    expect(html).toContain('model gpt-5-smart');
  });

  it('renders summary-only state before full trace details are loaded', () => {
    const html = renderToStaticMarkup(
      <SessionTraceExplorer
        summary={summary}
        trace={null}
        messages={messages}
        isLoadingSummary={false}
        isLoadingTrace={false}
        errorMessage={null}
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
        messages={messages}
        isLoadingSummary={false}
        isLoadingTrace={false}
        errorMessage="Failed to load trace details: boom"
        onLoadTrace={vi.fn()}
        onExport={vi.fn()}
      />,
    );

    expect(html).toContain('Failed to load trace details: boom');
  });
});
