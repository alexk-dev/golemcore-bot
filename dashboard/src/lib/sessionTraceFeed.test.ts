import { describe, expect, it } from 'vitest';

import type { MessageInfo, SessionTrace } from '../api/sessions';
import {
  buildSessionTraceFeed,
  findFeedItemById,
  type SessionTraceFeedSpanItem,
} from './sessionTraceFeed';

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
    timestamp: '2026-03-20T10:00:03Z',
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
    compressedSnapshotBytes: 1024,
    uncompressedSnapshotBytes: 2048,
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
      endedAt: '2026-03-20T10:00:03Z',
      truncated: false,
      compressedSnapshotBytes: 1024,
      uncompressedSnapshotBytes: 2048,
      spans: [
        {
          spanId: 'span-root',
          parentSpanId: null,
          name: 'web.message',
          kind: 'INGRESS',
          statusCode: 'OK',
          statusMessage: null,
          startedAt: '2026-03-20T10:00:00Z',
          endedAt: '2026-03-20T10:00:03Z',
          durationMs: 3000,
          attributes: {},
          events: [],
          snapshots: [],
        },
        {
          spanId: 'span-context',
          parentSpanId: 'span-root',
          name: 'system.ContextBuildingSystem',
          kind: 'SYSTEM',
          statusCode: 'OK',
          statusMessage: null,
          startedAt: '2026-03-20T10:00:00.200Z',
          endedAt: '2026-03-20T10:00:00.450Z',
          durationMs: 250,
          attributes: {},
          events: [
            {
              name: 'tier.resolved',
              timestamp: '2026-03-20T10:00:00.320Z',
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
          spanId: 'span-llm',
          parentSpanId: 'span-root',
          name: 'llm.chat',
          kind: 'LLM',
          statusCode: 'OK',
          statusMessage: null,
          startedAt: '2026-03-20T10:00:01Z',
          endedAt: '2026-03-20T10:00:02Z',
          durationMs: 1000,
          attributes: {
            'context.skill.name': 'executor',
            'context.model.tier': 'smart',
            'context.model.id': 'gpt-5-smart',
            'context.model.reasoning': 'high',
            'context.model.source': 'skill',
          },
          events: [
            {
              name: 'request.context',
              timestamp: '2026-03-20T10:00:01.010Z',
              attributes: {
                skill: 'executor',
                tier: 'smart',
                model_id: 'gpt-5-smart',
                source: 'skill',
              },
            },
          ],
          snapshots: [
            {
              snapshotId: 'snapshot-llm',
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
        },
      ],
    },
  ],
};

describe('sessionTraceFeed', () => {
  it('groups messages and service spans into a single user turn with trace tags', () => {
    const feed = buildSessionTraceFeed(messages, trace);

    expect(feed.turns).toHaveLength(1);
    expect(feed.turns[0].items.map((item) => item.id)).toEqual([
      'message-user',
      'span:trace-1:span-context',
      'span:trace-1:span-llm',
      'message-assistant',
    ]);

    const llmItem = findFeedItemById(feed.turns, 'span:trace-1:span-llm') as SessionTraceFeedSpanItem;
    expect(llmItem.tags.map((tag) => tag.label)).toContain('skill executor');
    expect(llmItem.tags.map((tag) => tag.label)).toContain('tier smart');
    expect(llmItem.tags.map((tag) => tag.label)).toContain('model gpt-5-smart');
    expect(llmItem.traceMeta?.traceId).toBe('trace-1');
    expect(llmItem.traceMeta?.spanId).toBe('span-llm');
    expect(llmItem.traceMeta?.source).toBe('skill');
    expect(llmItem.hasPayloadInspect).toBe(true);
  });

  it('captures transition and resolution events as readable trace notes', () => {
    const feed = buildSessionTraceFeed(messages, trace);
    const contextItem = findFeedItemById(feed.turns, 'span:trace-1:span-context') as SessionTraceFeedSpanItem;

    expect(contextItem.eventNotes).toContain('tier.resolved: executor -> smart -> gpt-5-smart');
    expect(contextItem.hasPayloadInspect).toBe(false);
  });
});
