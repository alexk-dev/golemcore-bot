import type { SessionTraceRecord, SessionTraceSpan } from '../api/sessions';

export interface TraceSelectionState {
  traceId: string | null;
  spanId: string | null;
}

export interface TraceTreeNode {
  span: SessionTraceSpan;
  children: TraceTreeNode[];
}

export function formatTraceDuration(durationMs: number | null): string {
  if (durationMs == null) {
    return '-';
  }
  if (durationMs < 1000) {
    return `${durationMs} ms`;
  }
  return `${(durationMs / 1000).toFixed(durationMs >= 10_000 ? 0 : 1)} s`;
}

export function formatTraceBytes(value: number | null): string {
  if (value == null || value <= 0) {
    return '0 B';
  }
  if (value < 1024) {
    return `${value} B`;
  }
  if (value < 1024 * 1024) {
    return `${(value / 1024).toFixed(1)} KB`;
  }
  return `${(value / (1024 * 1024)).toFixed(1)} MB`;
}

export function getTraceStatusVariant(statusCode: string | null): string {
  if (statusCode === 'ERROR') {
    return 'danger';
  }
  if (statusCode === 'OK') {
    return 'success';
  }
  return 'secondary';
}

export function buildTraceTree(spans: SessionTraceSpan[]): TraceTreeNode[] {
  const nodes = new Map<string, TraceTreeNode>();
  spans.forEach((span) => {
    nodes.set(span.spanId, {
      span,
      children: [],
    });
  });

  const roots: TraceTreeNode[] = [];
  spans.forEach((span) => {
    const node = nodes.get(span.spanId);
    if (node == null) {
      return;
    }
    if (span.parentSpanId == null) {
      roots.push(node);
      return;
    }
    const parent = nodes.get(span.parentSpanId);
    if (parent == null) {
      roots.push(node);
      return;
    }
    parent.children.push(node);
  });

  return roots;
}

export function flattenTraceTree(nodes: TraceTreeNode[], depth = 0): Array<{ span: SessionTraceSpan; depth: number }> {
  return nodes.flatMap((node) => [
    { span: node.span, depth },
    ...flattenTraceTree(node.children, depth + 1),
  ]);
}

export function getInitialTrace(trace: SessionTraceRecord[] | null | undefined): SessionTraceRecord | null {
  if (trace == null || trace.length === 0) {
    return null;
  }
  return trace[0];
}

export function getInitialSpan(record: SessionTraceRecord | null): SessionTraceSpan | null {
  if (record == null || record.spans.length === 0) {
    return null;
  }
  return record.spans[0];
}

export function resolveTraceSelection(
  traces: SessionTraceRecord[] | null | undefined,
  activeTraceId: string | null,
  activeSpanId: string | null,
  preferredTraceId: string | null,
): TraceSelectionState {
  const availableTraces = traces ?? [];
  if (availableTraces.length === 0) {
    return {
      traceId: null,
      spanId: null,
    };
  }

  const selectedTrace = availableTraces.find((item) => item.traceId === activeTraceId)
    ?? availableTraces.find((item) => item.traceId === preferredTraceId)
    ?? availableTraces[0];
  const selectedSpan = selectedTrace.spans.find((item) => item.spanId === activeSpanId)
    ?? selectedTrace.spans[0]
    ?? null;

  return {
    traceId: selectedTrace.traceId,
    spanId: selectedSpan?.spanId ?? null,
  };
}

export function getIndentClass(depth: number): string {
  if (depth <= 0) {
    return '';
  }
  if (depth === 1) {
    return 'ps-2';
  }
  if (depth === 2) {
    return 'ps-3';
  }
  if (depth === 3) {
    return 'ps-4';
  }
  return 'ps-5';
}
