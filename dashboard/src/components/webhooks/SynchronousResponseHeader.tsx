import type { ReactElement } from 'react';
import { cn } from '../../lib/utils';
import HelpTip from '../common/HelpTip';

interface SynchronousResponseHeaderProps {
  syncResponse: boolean;
  onToggle: (syncResponse: boolean) => void;
}

export function SynchronousResponseHeader({
  syncResponse,
  onToggle,
}: SynchronousResponseHeaderProps): ReactElement {
  return (
    <div className="flex flex-col gap-4 lg:flex-row lg:items-start lg:justify-between">
      <div className="space-y-1">
        <div className="flex items-center gap-2 text-sm font-semibold text-foreground">
          <span>Synchronous HTTP Response</span>
          <HelpTip text="Wait for the completed agent response and return it to the webhook caller." />
        </div>
        <p className="text-sm leading-6 text-muted-foreground">
          Return the final agent output in the webhook HTTP response body.
        </p>
      </div>

      <button
        type="button"
        role="switch"
        aria-checked={syncResponse}
        onClick={() => onToggle(!syncResponse)}
        className={cn(
          'inline-flex items-center gap-3 self-start rounded-lg border px-3 py-2 text-sm font-semibold transition-all',
          syncResponse
            ? 'border-primary/30 bg-primary/10 text-foreground shadow-soft'
            : 'border-border/80 bg-background/80 text-muted-foreground',
        )}
      >
        <span
          className={cn(
            'relative h-6 w-11 rounded-full transition-colors',
            syncResponse ? 'bg-primary' : 'bg-muted',
          )}
        >
          <span
            className={cn(
              'absolute left-0.5 top-0.5 h-5 w-5 rounded-full bg-white shadow transition-transform',
              syncResponse ? 'translate-x-5' : 'translate-x-0',
            )}
          />
        </span>
        <span>{syncResponse ? 'Enabled' : 'Disabled'}</span>
      </button>
    </div>
  );
}
