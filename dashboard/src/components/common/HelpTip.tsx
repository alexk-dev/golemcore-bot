import type { ReactElement } from 'react';
import { FiExternalLink, FiHelpCircle } from 'react-icons/fi';

export interface HelpTipProps {
  text: string;
  href?: string;
  linkLabel?: string;
}

export default function HelpTip({ text, href, linkLabel }: HelpTipProps): ReactElement {
  const tooltipClassName = href != null
    ? 'absolute bottom-[calc(100%+0.5rem)] left-1/2 z-30 hidden w-64 -translate-x-1/2 rounded-2xl border border-border/80 bg-card/95 px-3 py-2 text-xs leading-5 text-card-foreground shadow-2xl backdrop-blur-sm group-hover:block group-focus-within:block'
    : 'pointer-events-none absolute bottom-[calc(100%+0.5rem)] left-1/2 z-30 hidden w-56 -translate-x-1/2 rounded-2xl border border-border/80 bg-card/95 px-3 py-2 text-xs leading-5 text-card-foreground shadow-2xl backdrop-blur-sm group-hover:block group-focus-within:block';

  return (
    <span className="group relative inline-flex">
      <button
        type="button"
        className="setting-tip setting-tip-btn"
        aria-label={text}
      >
        <FiHelpCircle aria-hidden="true" focusable="false" />
      </button>
      <span role="tooltip" className={tooltipClassName}>
        <span>{text}</span>
        {href != null && (
          <a
            href={href}
            target="_blank"
            rel="noreferrer"
            className="mt-2 inline-flex items-center gap-1 text-[11px] font-semibold text-primary underline-offset-4 hover:underline"
          >
            <span>{linkLabel ?? 'Open docs'}</span>
            <FiExternalLink size={11} aria-hidden="true" />
          </a>
        )}
      </span>
    </span>
  );
}
