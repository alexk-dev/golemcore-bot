import { renderToStaticMarkup } from 'react-dom/server';
import type { ReactNode } from 'react';
import { describe, expect, it, vi } from 'vitest';

import type { SessionTraceFeedMessageItem, SessionTraceFeedSpanItem } from '../../lib/sessionTraceFeed';
import { SessionTraceBubble } from './SessionTraceBubble';
import { SessionTraceMetaModal } from './SessionTraceMetaModal';
import { SessionTracePayloadModal } from './SessionTracePayloadModal';

vi.mock('react-bootstrap', async () => {
  const actual = await vi.importActual('react-bootstrap');

  interface ModalProps {
    show?: boolean;
    children?: ReactNode;
  }

  function ModalStub({ show = false, children }: ModalProps): ReactNode {
    return show ? <div data-modal="true">{children}</div> : null;
  }

  function ModalSection({ children }: { children?: ReactNode }): ReactNode {
    return <div>{children}</div>;
  }

  return {
    ...actual,
    Modal: Object.assign(ModalStub, {
      Header: ModalSection,
      Title: ModalSection,
      Body: ModalSection,
    }),
  };
});

const spanItem: SessionTraceFeedSpanItem = {
  id: 'span:trace-1:span-llm',
  type: 'span',
  bubbleKind: 'llm',
  title: 'llm.chat',
  timestamp: '2026-03-20T10:00:01Z',
  content: 'request.context: executor -> smart -> gpt-5-smart',
  eventNotes: ['request.context: executor -> smart -> gpt-5-smart'],
  tags: [
    { label: 'skill executor', variant: 'info' },
    { label: 'tier smart', variant: 'secondary' },
    { label: 'model gpt-5-smart', variant: 'warning' },
  ],
  traceMeta: {
    traceId: 'trace-1',
    spanId: 'span-llm',
    parentSpanId: 'span-root',
    source: 'skill',
  },
  snapshots: [
    {
      snapshotId: 'snapshot-1',
      role: 'response',
      contentType: 'application/json',
      encoding: 'zstd',
      originalSize: 120,
      compressedSize: 48,
      truncated: false,
      payloadAvailable: true,
      payloadPreview: '{"answer":"done"}',
      payloadPreviewTruncated: false,
    },
  ],
  hasPayloadInspect: true,
};

const messageItem: SessionTraceFeedMessageItem = {
  id: 'message-1',
  type: 'message',
  role: 'assistant',
  title: 'Assistant',
  content: 'I found the issue.',
  timestamp: '2026-03-20T10:00:02Z',
  tags: [{ label: 'model gpt-5-smart', variant: 'warning' }],
};

describe('SessionTraceBubble', () => {
  it('renders trace and payload actions for service bubbles with stored payloads', () => {
    const html = renderToStaticMarkup(
      <SessionTraceBubble item={spanItem} onOpenMeta={vi.fn()} onOpenPayload={vi.fn()} />,
    );

    expect(html).toContain('Trace meta');
    expect(html).toContain('Payload inspect');
    expect(html).toContain('skill executor');
  });

  it('renders chat-style message bubbles without trace action buttons', () => {
    const html = renderToStaticMarkup(
      <SessionTraceBubble item={messageItem} onOpenMeta={vi.fn()} onOpenPayload={vi.fn()} />,
    );

    expect(html).toContain('I found the issue.');
    expect(html).not.toContain('Trace meta');
    expect(html).not.toContain('Payload inspect');
  });
});

describe('SessionTrace dialogs', () => {
  it('renders trace metadata in a dedicated modal', () => {
    const html = renderToStaticMarkup(
      <SessionTraceMetaModal meta={spanItem.traceMeta} onHide={vi.fn()} />,
    );

    expect(html).toContain('trace-1');
    expect(html).toContain('span-llm');
    expect(html).toContain('skill');
  });

  it('renders payload preview in a dedicated inspect modal', () => {
    const html = renderToStaticMarkup(
      <SessionTracePayloadModal item={spanItem} onHide={vi.fn()} />,
    );

    expect(html).toContain('Payload inspect');
    expect(html).toContain('Payload preview');
    expect(html).toContain('{&quot;answer&quot;:&quot;done&quot;}');
  });
});
