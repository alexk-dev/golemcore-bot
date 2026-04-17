import type { AnchorHTMLAttributes, ReactElement, ReactNode } from 'react';
import { FiExternalLink } from 'react-icons/fi';

import type { DocLink } from '../../lib/docsLinks';
import { cn } from '../../lib/utils';

const BUTTON_CLASS_NAME = 'inline-flex items-center justify-center gap-2 whitespace-nowrap rounded-xl border border-border/90 bg-card/80 px-3 py-2 text-xs font-semibold text-foreground shadow-soft transition-all duration-200 hover:border-primary/40 hover:bg-card';
const TEXT_CLASS_NAME = 'inline-flex items-center gap-1 text-sm font-semibold text-primary underline-offset-4 hover:underline';

export interface DocsLinkAnchorProps extends Omit<AnchorHTMLAttributes<HTMLAnchorElement>, 'href'> {
  doc: DocLink;
  children?: ReactNode;
  appearance?: 'button' | 'text';
}

export function DocsLinkAnchor({
  doc,
  children,
  appearance = 'button',
  className,
  ...props
}: DocsLinkAnchorProps): ReactElement {
  return (
    <a
      {...props}
      href={doc.url}
      target="_blank"
      rel="noreferrer"
      title={`Open ${doc.title} on docs.golemcore.me`}
      className={cn(appearance === 'button' ? BUTTON_CLASS_NAME : TEXT_CLASS_NAME, className)}
    >
      <span>{children ?? doc.shortLabel}</span>
      <FiExternalLink size={appearance === 'button' ? 14 : 12} aria-hidden="true" />
    </a>
  );
}
