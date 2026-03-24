import type {
  MessageInfo,
  SessionTrace,
  SessionTraceSnapshot,
  SessionTraceSpan,
  SessionTraceSpanEvent,
} from '../api/sessions';
import { formatTraceDuration } from './traceFormat';

export interface SessionTraceFeedTag {
  label: string;
  variant: 'secondary' | 'info' | 'success' | 'warning' | 'danger';
}

export interface SessionTraceFeedMeta {
  traceId: string;
  spanId: string | null;
  parentSpanId: string | null;
  source: string | null;
}

export interface SessionTraceFeedMessageItem {
  id: string;
  type: 'message';
  role: string;
  title: string;
  content: string;
  timestamp: string | null;
  tags: SessionTraceFeedTag[];
}

export interface SessionTraceFeedSpanItem {
  id: string;
  type: 'span';
  bubbleKind: 'system' | 'llm' | 'tool' | 'outbound';
  title: string;
  timestamp: string | null;
  content: string | null;
  eventNotes: string[];
  tags: SessionTraceFeedTag[];
  traceMeta: SessionTraceFeedMeta | null;
  snapshots: SessionTraceSnapshot[];
  hasPayloadInspect: boolean;
}

export type SessionTraceFeedItem = SessionTraceFeedMessageItem | SessionTraceFeedSpanItem;

export interface SessionTraceFeedTurn {
  id: string;
  title: string;
  timestamp: string | null;
  traceNames: string[];
  items: SessionTraceFeedItem[];
}

export interface SessionTraceFeed {
  turns: SessionTraceFeedTurn[];
}

interface FlatFeedItem {
  item: SessionTraceFeedItem;
  sortTime: number;
  priority: number;
  turnSeedTitle: string;
  traceName: string | null;
}

function parseTimestamp(value: string | null): number {
  if (value == null || value.length === 0) {
    return Number.MAX_SAFE_INTEGER;
  }
  const parsed = Date.parse(value);
  return Number.isNaN(parsed) ? Number.MAX_SAFE_INTEGER : parsed;
}

function buildMessageTags(message: MessageInfo): SessionTraceFeedTag[] {
  const tags: SessionTraceFeedTag[] = [];
  if (message.skill != null && message.skill.length > 0) {
    tags.push({ label: `skill ${message.skill}`, variant: 'info' });
  }
  if (message.modelTier != null && message.modelTier.length > 0) {
    tags.push({ label: `tier ${message.modelTier}`, variant: 'secondary' });
  }
  if (message.model != null && message.model.length > 0) {
    tags.push({ label: `model ${message.model}`, variant: 'warning' });
  }
  if (message.reasoning != null && message.reasoning.length > 0) {
    tags.push({ label: `reasoning ${message.reasoning}`, variant: 'secondary' });
  }
  return tags;
}

function buildMessageItem(message: MessageInfo): SessionTraceFeedMessageItem {
  return {
    id: message.id,
    type: 'message',
    role: message.role,
    title: message.role === 'user' ? 'User' : message.role === 'assistant' ? 'Assistant' : message.role,
    content: message.content,
    timestamp: message.timestamp,
    tags: buildMessageTags(message),
  };
}

function inferSource(span: SessionTraceSpan): string | null {
  const attrSource = span.attributes['context.model.source'];
  if (typeof attrSource === 'string' && attrSource.length > 0) {
    return attrSource;
  }
  for (const event of span.events) {
    const eventSource = event.attributes.source;
    if (typeof eventSource === 'string' && eventSource.length > 0) {
      return eventSource;
    }
  }
  return null;
}

function buildBaseSpanTags(span: SessionTraceSpan): SessionTraceFeedTag[] {
  const tags: SessionTraceFeedTag[] = [];
  if (span.kind != null && span.kind.length > 0) {
    tags.push({ label: span.kind.toLowerCase(), variant: 'secondary' });
  }
  if (span.statusCode != null && span.statusCode.length > 0) {
    tags.push({
      label: span.statusCode,
      variant: span.statusCode === 'ERROR' ? 'danger' : 'success',
    });
  }
  if (span.durationMs != null) {
    tags.push({ label: formatTraceDuration(span.durationMs), variant: 'secondary' });
  }
  return tags;
}

function appendContextTags(tags: SessionTraceFeedTag[], span: SessionTraceSpan): void {
  const skill = span.attributes['context.skill.name'];
  if (typeof skill === 'string' && skill.length > 0) {
    tags.push({ label: `skill ${skill}`, variant: 'info' });
  }
  const tier = span.attributes['context.model.tier'];
  if (typeof tier === 'string' && tier.length > 0) {
    tags.push({ label: `tier ${tier}`, variant: 'secondary' });
  }
  const model = span.attributes['context.model.id'];
  if (typeof model === 'string' && model.length > 0) {
    tags.push({ label: `model ${model}`, variant: 'warning' });
  }
  const reasoning = span.attributes['context.model.reasoning'];
  if (typeof reasoning === 'string' && reasoning.length > 0) {
    tags.push({ label: `reasoning ${reasoning}`, variant: 'secondary' });
  }
}

function formatEventTriple(
  eventName: string,
  first: unknown,
  second: unknown,
  third: unknown,
): string {
  return `${eventName}: ${String(first ?? '-')} -> ${String(second ?? '-')} -> ${String(third ?? '-')}`;
}

function formatEventTransition(
  eventName: string,
  fromValue: unknown,
  toValue: unknown,
): string {
  return `${eventName}: ${String(fromValue ?? '-')} -> ${String(toValue ?? '-')}`;
}

function formatTierTransitionNote(event: SessionTraceSpanEvent): string {
  return `${event.name}: ${String(event.attributes.from_tier ?? '-')} / ${String(event.attributes.from_model_id ?? '-')} -> ${String(event.attributes.to_tier ?? '-')} / ${String(event.attributes.to_model_id ?? '-')}`;
}

function buildEventNote(event: SessionTraceSpanEvent): string {
  switch (event.name) {
    case null:
      return 'event';
    case 'request.context':
      return formatEventTriple(event.name, event.attributes.skill, event.attributes.tier, event.attributes.model_id);
    case 'skill.transition.requested':
    case 'skill.transition.applied':
      return formatEventTransition(event.name, event.attributes.from_skill, event.attributes.to_skill);
    case 'tier.resolved':
      return formatEventTriple(event.name, event.attributes.skill, event.attributes.tier, event.attributes.model_id);
    case 'tier.transition':
      return formatTierTransitionNote(event);
    default:
      return event.name ?? 'event';
  }
}

function appendEventTags(tags: SessionTraceFeedTag[], span: SessionTraceSpan): void {
  for (const event of span.events) {
    if (event.name === 'tier.resolved') {
      const skill = event.attributes.skill;
      const tier = event.attributes.tier;
      const model = event.attributes.model_id;
      if (typeof skill === 'string' && skill.length > 0) {
        tags.push({ label: `skill ${skill}`, variant: 'info' });
      }
      if (typeof tier === 'string' && tier.length > 0) {
        tags.push({ label: `tier ${tier}`, variant: 'secondary' });
      }
      if (typeof model === 'string' && model.length > 0) {
        tags.push({ label: `model ${model}`, variant: 'warning' });
      }
    }
    if (event.name === 'tier.transition') {
      const nextTier = event.attributes.to_tier;
      const nextModel = event.attributes.to_model_id;
      if (typeof nextTier === 'string' && nextTier.length > 0) {
        tags.push({ label: `tier ${nextTier}`, variant: 'secondary' });
      }
      if (typeof nextModel === 'string' && nextModel.length > 0) {
        tags.push({ label: `model ${nextModel}`, variant: 'warning' });
      }
    }
  }
}

function dedupeTags(tags: SessionTraceFeedTag[]): SessionTraceFeedTag[] {
  const seen = new Set<string>();
  return tags.filter((tag) => {
    if (seen.has(tag.label)) {
      return false;
    }
    seen.add(tag.label);
    return true;
  });
}

function resolveBubbleKind(span: SessionTraceSpan): SessionTraceFeedSpanItem['bubbleKind'] {
  if (span.kind === 'LLM') {
    return 'llm';
  }
  if (span.kind === 'TOOL') {
    return 'tool';
  }
  if (span.kind === 'OUTBOUND') {
    return 'outbound';
  }
  return 'system';
}

function buildSpanContent(span: SessionTraceSpan, eventNotes: string[]): string | null {
  if (span.statusMessage != null && span.statusMessage.length > 0) {
    return span.statusMessage;
  }
  if (eventNotes.length > 0) {
    return eventNotes[0];
  }
  return null;
}

function buildSpanItem(
  traceId: string,
  span: SessionTraceSpan,
): SessionTraceFeedSpanItem {
  const tags = buildBaseSpanTags(span);
  appendContextTags(tags, span);
  appendEventTags(tags, span);
  const eventNotes = span.events.map((event) => buildEventNote(event));
  const source = inferSource(span);

  return {
    id: `span:${traceId}:${span.spanId}`,
    type: 'span',
    bubbleKind: resolveBubbleKind(span),
    title: span.name ?? span.spanId,
    timestamp: span.startedAt,
    content: buildSpanContent(span, eventNotes),
    eventNotes,
    tags: dedupeTags(tags),
    traceMeta: {
      traceId,
      spanId: span.spanId,
      parentSpanId: span.parentSpanId,
      source,
    },
    snapshots: span.snapshots,
    hasPayloadInspect: span.snapshots.some((snapshot) => snapshot.payloadAvailable),
  };
}

function buildFlatItems(messages: MessageInfo[], trace: SessionTrace | null): FlatFeedItem[] {
  const flatItems: FlatFeedItem[] = messages.map((message) => ({
    item: buildMessageItem(message),
    sortTime: parseTimestamp(message.timestamp),
    priority: message.role === 'user' ? 0 : message.role === 'assistant' ? 3 : 4,
    turnSeedTitle: message.role === 'user' ? 'User turn' : 'System turn',
    traceName: null,
  }));

  if (trace == null) {
    return flatItems.sort((left, right) => left.sortTime - right.sortTime || left.priority - right.priority);
  }

  for (const record of trace.traces) {
    for (const span of record.spans) {
      if (span.spanId === record.rootSpanId) {
        continue;
      }
      flatItems.push({
        item: buildSpanItem(record.traceId, span),
        sortTime: parseTimestamp(span.startedAt),
        priority: 1,
        turnSeedTitle: 'System turn',
        traceName: record.traceName,
      });
    }
  }

  return flatItems.sort((left, right) => left.sortTime - right.sortTime || left.priority - right.priority);
}

function createTurn(index: number, title: string, timestamp: string | null): SessionTraceFeedTurn {
  return {
    id: `turn-${index}`,
    title,
    timestamp,
    traceNames: [],
    items: [],
  };
}

export function buildSessionTraceFeed(messages: MessageInfo[], trace: SessionTrace | null): SessionTraceFeed {
  const flatItems = buildFlatItems(messages, trace);
  if (flatItems.length === 0) {
    return { turns: [] };
  }

  const turns: SessionTraceFeedTurn[] = [];
  let currentTurn: SessionTraceFeedTurn | null = null;

  flatItems.forEach((entry) => {
    const isUserMessage = entry.item.type === 'message' && entry.item.role === 'user';
    if (currentTurn == null || isUserMessage) {
      currentTurn = createTurn(turns.length + 1, entry.turnSeedTitle, entry.item.timestamp);
      turns.push(currentTurn);
    }

    currentTurn.items.push(entry.item);
    if (entry.traceName != null && entry.traceName.length > 0 && !currentTurn.traceNames.includes(entry.traceName)) {
      currentTurn.traceNames.push(entry.traceName);
    }
  });

  return { turns };
}

export function findFeedItemById(turns: SessionTraceFeedTurn[], itemId: string): SessionTraceFeedItem | null {
  for (const turn of turns) {
    const item = turn.items.find((candidate) => candidate.id === itemId);
    if (item != null) {
      return item;
    }
  }
  return null;
}
