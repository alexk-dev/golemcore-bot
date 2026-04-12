import type { SessionTraceSpan, SessionTraceSpanEvent } from '../api/sessions';
import type { SessionTraceFeedTag } from './sessionTraceFeed';

interface EventTagDescriptor {
  label: string;
  variant: SessionTraceFeedTag['variant'];
}

function buildTierResolvedTags(event: SessionTraceSpanEvent): EventTagDescriptor[] {
  const tags: EventTagDescriptor[] = [
    { label: `skill ${String(event.attributes.skill)}`, variant: 'info' },
    { label: `tier ${String(event.attributes.tier)}`, variant: 'secondary' },
    { label: `model ${String(event.attributes.model_id)}`, variant: 'warning' },
  ];
  return tags.filter((tag) => !tag.label.endsWith(' undefined') && !tag.label.endsWith(' null'));
}

function buildTierTransitionTags(event: SessionTraceSpanEvent): EventTagDescriptor[] {
  const tags: EventTagDescriptor[] = [
    { label: `tier ${String(event.attributes.to_tier)}`, variant: 'secondary' },
    { label: `model ${String(event.attributes.to_model_id)}`, variant: 'warning' },
  ];
  return tags.filter((tag) => !tag.label.endsWith(' undefined') && !tag.label.endsWith(' null'));
}

function buildTacticSearchTags(event: SessionTraceSpanEvent): EventTagDescriptor[] {
  const tags: EventTagDescriptor[] = [];
  const tacticTitle = event.attributes['tactic.selected_title'];
  const artifactType = event.attributes['tactic.artifact_type'];
  const searchMode = event.attributes['tactic.search_mode'];
  if (typeof tacticTitle === 'string' && tacticTitle.length > 0) {
    tags.push({ label: `tactic ${tacticTitle}`, variant: 'info' });
  }
  if (typeof artifactType === 'string' && artifactType.length > 0) {
    tags.push({ label: artifactType, variant: 'secondary' });
  }
  if (typeof searchMode === 'string' && searchMode.length > 0) {
    tags.push({ label: `search ${searchMode}`, variant: 'secondary' });
  }
  return tags;
}

function buildEventTags(event: SessionTraceSpanEvent): EventTagDescriptor[] {
  if (event.name === 'tier.resolved') {
    return buildTierResolvedTags(event);
  }
  if (event.name === 'tier.transition') {
    return buildTierTransitionTags(event);
  }
  if (event.name === 'tactic.search.completed') {
    return buildTacticSearchTags(event);
  }
  return [];
}

export function appendEventTags(tags: SessionTraceFeedTag[], span: SessionTraceSpan): void {
  for (const event of span.events) {
    for (const tag of buildEventTags(event)) {
      tags.push(tag);
    }
  }
}
