import type { MemoryType, RelevantMemoryItem } from '../../../../api/memory';

const TYPE_LABELS: Record<MemoryType, string> = {
  DECISION: 'Decision',
  CONSTRAINT: 'Constraint',
  FAILURE: 'Failure',
  FIX: 'Fix',
  PREFERENCE: 'Preference',
  PROJECT_FACT: 'Fact',
  TASK_STATE: 'Task',
  COMMAND_RESULT: 'Command',
};

export type MemoryTypeFilter = 'all' | MemoryType;

export function getTypeLabel(type: MemoryType | null): string {
  if (type == null) {
    return 'Memory';
  }
  return TYPE_LABELS[type] ?? type;
}

export function getTypeTone(type: MemoryType | null): 'fact' | 'preference' | 'constraint' | 'failure' | 'task' | 'neutral' {
  switch (type) {
    case 'PROJECT_FACT':
    case 'COMMAND_RESULT':
      return 'fact';
    case 'PREFERENCE':
      return 'preference';
    case 'CONSTRAINT':
    case 'DECISION':
      return 'constraint';
    case 'FAILURE':
    case 'FIX':
      return 'failure';
    case 'TASK_STATE':
      return 'task';
    case null:
      return 'neutral';
  }
}

export function formatRelativeTime(iso: string | null, now: number = Date.now()): string {
  if (iso == null) {
    return '—';
  }
  const ms = new Date(iso).getTime();
  if (Number.isNaN(ms)) {
    return iso;
  }
  const deltaSeconds = Math.max(0, Math.floor((now - ms) / 1000));
  if (deltaSeconds < 45) {
    return 'Just now';
  }
  if (deltaSeconds < 60 * 60) {
    const minutes = Math.max(1, Math.round(deltaSeconds / 60));
    return `${minutes} min${minutes === 1 ? '' : 's'} ago`;
  }
  if (deltaSeconds < 60 * 60 * 24) {
    const hours = Math.max(1, Math.round(deltaSeconds / 3600));
    return `${hours} hour${hours === 1 ? '' : 's'} ago`;
  }
  const days = Math.max(1, Math.round(deltaSeconds / 86_400));
  return `${days} day${days === 1 ? '' : 's'} ago`;
}

export function describeReferences(item: RelevantMemoryItem): string {
  if (item.referenceCount <= 0) {
    return 'In session';
  }
  return `In ${item.referenceCount} session${item.referenceCount === 1 ? '' : 's'}`;
}

export function buildTypeFilterOptions(): ReadonlyArray<{ value: MemoryTypeFilter; label: string }> {
  return [
    { value: 'all', label: 'All types' },
    { value: 'PROJECT_FACT', label: 'Facts' },
    { value: 'PREFERENCE', label: 'Preferences' },
    { value: 'CONSTRAINT', label: 'Constraints' },
    { value: 'DECISION', label: 'Decisions' },
    { value: 'FAILURE', label: 'Failures' },
    { value: 'FIX', label: 'Fixes' },
    { value: 'TASK_STATE', label: 'Tasks' },
    { value: 'COMMAND_RESULT', label: 'Commands' },
  ];
}

export function filterMemoryItems(
  items: RelevantMemoryItem[],
  type: MemoryTypeFilter,
  search: string,
): RelevantMemoryItem[] {
  const trimmed = search.trim().toLowerCase();
  return items.filter((item) => {
    if (type !== 'all' && item.type !== type) {
      return false;
    }
    if (trimmed.length === 0) {
      return true;
    }
    const haystack = `${item.title ?? ''} ${item.content ?? ''} ${item.tags.join(' ')}`.toLowerCase();
    return haystack.includes(trimmed);
  });
}
