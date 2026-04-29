import type { ReactNode } from 'react';

export type StatusPillTone = 'success' | 'warning' | 'danger' | 'accent' | 'neutral';

interface StatusPillProps {
  tone?: StatusPillTone;
  showDot?: boolean;
  icon?: ReactNode;
  children: ReactNode;
  ariaLabel?: string;
  title?: string;
}

export default function StatusPill({
  tone = 'neutral',
  showDot = false,
  icon,
  children,
  ariaLabel,
  title,
}: StatusPillProps) {
  return (
    <span
      className={`agent-pill agent-pill--${tone}`}
      role="status"
      aria-label={ariaLabel}
      title={title}
    >
      {showDot && <span className="agent-pill__dot" aria-hidden="true" />}
      {icon != null && <span aria-hidden="true">{icon}</span>}
      <span>{children}</span>
    </span>
  );
}
