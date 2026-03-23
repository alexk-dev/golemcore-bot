import { describe, expect, it } from 'vitest';

import type { SessionTraceRecord, SessionTraceSpan } from '../api/sessions';
import {
  buildTraceTree,
  flattenTraceTree,
  formatTraceBytes,
  formatTraceDuration,
  resolveTraceSelection,
} from './traceFormat';

const spans: SessionTraceSpan[] = [
  {
    spanId: 'root',
    parentSpanId: null,
    name: 'web.request',
    kind: 'INGRESS',
    statusCode: 'OK',
    statusMessage: null,
    startedAt: '2026-03-20T10:00:00Z',
    endedAt: '2026-03-20T10:00:02Z',
    durationMs: 2000,
    attributes: {},
    events: [],
    snapshots: [],
  },
  {
    spanId: 'child',
    parentSpanId: 'root',
    name: 'llm.chat',
    kind: 'LLM',
    statusCode: 'OK',
    statusMessage: null,
    startedAt: '2026-03-20T10:00:00.200Z',
    endedAt: '2026-03-20T10:00:01.200Z',
    durationMs: 1000,
    attributes: {},
    events: [],
    snapshots: [],
  },
];

const traceRecords: SessionTraceRecord[] = [
  {
    traceId: 'trace-1',
    rootSpanId: 'root',
    traceName: 'trace-1',
    startedAt: '2026-03-20T10:00:00Z',
    endedAt: '2026-03-20T10:00:02Z',
    truncated: false,
    compressedSnapshotBytes: 0,
    uncompressedSnapshotBytes: 0,
    spans,
  },
  {
    traceId: 'trace-2',
    rootSpanId: 'trace-2-root',
    traceName: 'trace-2',
    startedAt: '2026-03-20T10:01:00Z',
    endedAt: '2026-03-20T10:01:01Z',
    truncated: false,
    compressedSnapshotBytes: 0,
    uncompressedSnapshotBytes: 0,
    spans: [
      {
        spanId: 'trace-2-root',
        parentSpanId: null,
        name: 'tool.call',
        kind: 'TOOL',
        statusCode: 'OK',
        statusMessage: null,
        startedAt: '2026-03-20T10:01:00Z',
        endedAt: '2026-03-20T10:01:01Z',
        durationMs: 1000,
        attributes: {},
        events: [],
        snapshots: [],
      },
    ],
  },
];

describe('traceFormat', () => {
  it('formats duration and byte counts for the explorer', () => {
    expect(formatTraceDuration(950)).toBe('950 ms');
    expect(formatTraceDuration(2300)).toBe('2.3 s');
    expect(formatTraceBytes(512)).toBe('512 B');
    expect(formatTraceBytes(2048)).toBe('2.0 KB');
  });

  it('builds and flattens the trace tree with depth metadata', () => {
    const tree = buildTraceTree(spans);
    const rows = flattenTraceTree(tree);

    expect(tree).toHaveLength(1);
    expect(tree[0].children).toHaveLength(1);
    expect(rows).toEqual([
      { span: spans[0], depth: 0 },
      { span: spans[1], depth: 1 },
    ]);
  });

  it('preserves the selected trace and span when they still exist after refetch', () => {
    expect(resolveTraceSelection(traceRecords, 'trace-1', 'child', null)).toEqual({
      traceId: 'trace-1',
      spanId: 'child',
    });
  });

  it('falls back to the preferred or first trace when selection is missing', () => {
    expect(resolveTraceSelection(traceRecords, 'missing', 'missing', 'trace-2')).toEqual({
      traceId: 'trace-2',
      spanId: 'trace-2-root',
    });
  });
});
