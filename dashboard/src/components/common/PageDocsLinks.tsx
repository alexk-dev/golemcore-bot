import type { ReactElement } from 'react';

import type { DocLink } from '../../lib/docsLinks';
import { cn } from '../../lib/utils';
import { DocsLinkAnchor } from './DocsLinkAnchor';

export interface PageDocsLinksProps {
  title?: string;
  docs: DocLink[];
  className?: string;
}

export function PageDocsLinks({
  title = 'Relevant docs',
  docs,
  className,
}: PageDocsLinksProps): ReactElement | null {
  if (docs.length === 0) {
    return null;
  }

  return (
    <div className={cn('space-y-2', className)}>
      <div className="text-[0.72rem] font-semibold uppercase tracking-[0.24em] text-primary/80">
        {title}
      </div>
      <div className="flex flex-wrap gap-2">
        {docs.map((doc) => (
          <DocsLinkAnchor key={doc.id} doc={doc} />
        ))}
      </div>
    </div>
  );
}
