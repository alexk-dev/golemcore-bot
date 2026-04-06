import type { ReactElement } from 'react';

import type { SelfEvolvingRunDetail } from '../../api/selfEvolving';
import { shortId, formatTimestamp, statusBadgeClass, humanizeStatus } from './selfEvolvingUi';

interface SelfEvolvingVerdictPanelProps {
  run: SelfEvolvingRunDetail | undefined;
  isLoading: boolean;
}

function renderRunHeader(run: SelfEvolvingRunDetail): ReactElement {
  return (
    <div className="rounded-xl bg-muted/30 border border-border/60 p-3 mb-4 text-sm">
      <div className="grid grid-cols-2 gap-x-6 gap-y-1.5">
        <div>
          <span className="text-xs text-muted-foreground">Session</span>
          <div className="font-mono text-xs" title={run.sessionId ?? undefined}>{shortId(run.sessionId ?? 'n/a')}</div>
        </div>
        <div>
          <span className="text-xs text-muted-foreground">Trace</span>
          <div className="font-mono text-xs" title={run.traceId ?? undefined}>{shortId(run.traceId ?? 'n/a')}</div>
        </div>
        <div>
          <span className="text-xs text-muted-foreground">Bundle</span>
          <div className="font-mono text-xs" title={run.artifactBundleId ?? undefined}>{shortId(run.artifactBundleId ?? 'n/a')}</div>
        </div>
        <div>
          <span className="text-xs text-muted-foreground">Completed</span>
          <div className="text-xs">{formatTimestamp(run.completedAt)}</div>
        </div>
      </div>
    </div>
  );
}

export function SelfEvolvingVerdictPanel({
  run,
  isLoading,
}: SelfEvolvingVerdictPanelProps): ReactElement {
  if (isLoading) {
    return (
      <div className="card card-body animate-pulse">
        <div className="h-4 bg-muted rounded w-2/3 mb-3" />
        <div className="h-3 bg-muted rounded w-full mb-2" />
        <div className="h-3 bg-muted rounded w-5/6" />
      </div>
    );
  }

  if (run == null) {
    return (
      <div className="card card-body">
        <h5 className="text-base font-semibold tracking-tight mb-1">Verdict Panel</h5>
        <p className="text-sm text-muted-foreground">
          Select a run from the table to see how the judge evaluated it.
        </p>
      </div>
    );
  }

  if (run.verdict == null) {
    return (
      <div className="card card-body">
        <div className="flex flex-wrap items-center gap-2 mb-3">
          <h5 className="text-base font-semibold tracking-tight mb-0">Verdict Panel</h5>
          <span className={`badge ${statusBadgeClass(run.status)}`}>{humanizeStatus(run.status)}</span>
        </div>
        <div className="mb-2">
          <span className="font-semibold text-sm" title={run.id}>Run {shortId(run.id)}</span>
          <span className="text-xs text-muted-foreground ml-2">{run.golemId ?? 'unknown'} &middot; {formatTimestamp(run.startedAt)}</span>
        </div>
        {renderRunHeader(run)}
        <p className="text-sm text-muted-foreground">The judge has not evaluated this run yet. A verdict will appear once analysis completes.</p>
      </div>
    );
  }

  return (
    <div className="card card-body">
      <div className="flex flex-wrap items-center gap-2 mb-3">
        <h5 className="text-base font-semibold tracking-tight mb-0">Verdict Panel</h5>
        <span className={`badge ${statusBadgeClass(run.verdict.outcomeStatus)}`} title="Outcome verdict from the judge">
          {humanizeStatus(run.verdict.outcomeStatus)}
        </span>
        {run.verdict.processStatus != null && run.verdict.processStatus !== run.verdict.outcomeStatus && (
          <span className={`badge ${statusBadgeClass(run.verdict.processStatus)}`} title="Process quality assessment">
            {humanizeStatus(run.verdict.processStatus)}
          </span>
        )}
        {run.verdict.confidence != null && (
          <span className="text-xs text-muted-foreground" title="How confident the judge is in this verdict (0\u20131)">
            Confidence {run.verdict.confidence.toFixed(2)}
          </span>
        )}
      </div>

      <div className="mb-2">
        <span className="font-semibold text-sm" title={run.id}>Run {shortId(run.id)}</span>
        <span className="text-xs text-muted-foreground ml-2">{run.golemId ?? 'unknown'} &middot; {formatTimestamp(run.startedAt)}</span>
      </div>

      {renderRunHeader(run)}

      <div className="mb-4">
        <span className="text-xs font-medium uppercase tracking-wider text-muted-foreground" title="Summary of what the agent accomplished">Outcome</span>
        <p className="text-sm mt-1">{run.verdict.outcomeSummary ?? 'No outcome summary available.'}</p>
      </div>

      <div className="mb-4">
        <span className="text-xs font-medium uppercase tracking-wider text-muted-foreground" title="How efficiently the agent executed — tool use, routing, escalation">Process</span>
        <p className="text-sm mt-1">{run.verdict.processSummary ?? 'No process summary available.'}</p>
      </div>

      <div>
        <span className="text-xs font-medium uppercase tracking-wider text-muted-foreground">Process Findings</span>
        {run.verdict.processFindings.length === 0 ? (
          <p className="text-sm text-muted-foreground mt-1">No findings recorded.</p>
        ) : (
          <ul className="mt-1 pl-4 text-sm list-disc space-y-0.5">
            {run.verdict.processFindings.map((finding) => (
              <li key={finding}>{finding}</li>
            ))}
          </ul>
        )}
      </div>
    </div>
  );
}
