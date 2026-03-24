import type { ReactElement } from 'react';
import { FiHelpCircle } from 'react-icons/fi';

interface HelpTipProps {
  text: string;
}

export default function HelpTip({ text }: HelpTipProps): ReactElement {
  return (
    <span className="group relative inline-flex">
      <button
        type="button"
        className="setting-tip setting-tip-btn"
        aria-label={text}
      >
        <FiHelpCircle aria-hidden="true" focusable="false" />
      </button>
      <span
        role="tooltip"
        className="pointer-events-none absolute bottom-[calc(100%+0.5rem)] left-1/2 z-30 hidden w-56 -translate-x-1/2 rounded-2xl border border-border/80 bg-card/95 px-3 py-2 text-xs leading-5 text-card-foreground shadow-2xl backdrop-blur-sm group-hover:block group-focus-within:block"
      >
        {text}
      </span>
    </span>
  );
}
