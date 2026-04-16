import type { ReactElement } from 'react';
import { FiChevronRight } from 'react-icons/fi';

export interface IdeBreadcrumbsProps {
  path: string | null;
  onOpenPath: (path: string) => void;
}

export function IdeBreadcrumbs({ path, onOpenPath }: IdeBreadcrumbsProps): ReactElement {
  if (path == null || path.length === 0) {
    return (
      <div className="ide-breadcrumbs border-b border-border/80 px-4 py-2 text-xs text-muted-foreground">
        No file selected
      </div>
    );
  }

  const segments = path.split('/').filter((segment) => segment.length > 0);
  return (
    <nav className="ide-breadcrumbs flex flex-wrap items-center gap-1 border-b border-border/80 px-4 py-2 text-xs" aria-label="File breadcrumbs">
      {segments.map((segment, index) => {
        const segmentPath = segments.slice(0, index + 1).join('/');
        const isLast = index === segments.length - 1;
        return (
          <span key={segmentPath} className="inline-flex items-center gap-1">
            <button
              type="button"
              className={isLast ? 'font-semibold text-foreground' : 'text-muted-foreground hover:text-foreground'}
              onClick={() => onOpenPath(segmentPath)}
              disabled={!isLast}
              title={segmentPath}
            >
              {segment}
            </button>
            {!isLast && <FiChevronRight size={12} className="text-muted-foreground" />}
          </span>
        );
      })}
    </nav>
  );
}
