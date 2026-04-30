import { FiMoreHorizontal } from 'react-icons/fi';
import type { RelevantMemoryItem } from '../../../../api/memory';
import { describeReferences, formatRelativeTime, getTypeLabel, getTypeTone } from './memoryFormat';

interface MemoryArtifactCardProps {
  item: RelevantMemoryItem;
  now: number;
}

function fallbackTitle(item: RelevantMemoryItem): string {
  if (item.title != null && item.title.trim().length > 0) {
    return item.title;
  }
  if (item.content != null && item.content.trim().length > 0) {
    const firstLine = item.content.split('\n')[0] ?? '';
    return firstLine.length > 60 ? `${firstLine.slice(0, 57)}…` : firstLine;
  }
  return 'Untitled memory';
}

export default function MemoryArtifactCard({ item, now }: MemoryArtifactCardProps) {
  const tone = getTypeTone(item.type);
  const typeLabel = getTypeLabel(item.type);
  const updatedRelative = formatRelativeTime(item.updatedAt ?? item.createdAt, now);

  return (
    <article className={`memory-card memory-card--${tone}`} aria-label={`Memory ${fallbackTitle(item)}`}>
      <header className="memory-card__header">
        <span className="memory-card__title">{fallbackTitle(item)}</span>
        <span className={`memory-card__tag memory-card__tag--${tone}`}>{typeLabel}</span>
        <button
          type="button"
          className="memory-card__menu"
          aria-label="More memory actions"
          title="More"
        >
          <FiMoreHorizontal size={14} aria-hidden="true" />
        </button>
      </header>
      {item.content != null && item.content.trim().length > 0 && (
        <p className="memory-card__content">{item.content}</p>
      )}
      <footer className="memory-card__footer">
        <span>Added {updatedRelative}</span>
        <span className="memory-card__separator" aria-hidden="true">·</span>
        <span>{describeReferences(item)}</span>
      </footer>
    </article>
  );
}
