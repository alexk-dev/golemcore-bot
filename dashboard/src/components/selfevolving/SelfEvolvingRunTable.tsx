import type { ReactElement } from 'react';

import type { SelfEvolvingRunSummary } from '../../api/selfEvolving';
import { shortId, formatTimestamp, statusBadgeClass, humanizeStatus } from './selfEvolvingUi';

interface SelfEvolvingRunTableProps {
  runs: SelfEvolvingRunSummary[];
  selectedRunId: string | null;
  onSelectRun: (runId: string) => void;
}

export function SelfEvolvingRunTable({
  runs,
  selectedRunId,
  onSelectRun,
}: SelfEvolvingRunTableProps): ReactElement {
  return (
    <div className="card">
      <div className="card-body">
        <div className="flex items-center justify-between gap-2 mb-3">
          <div>
            <h5 className="text-base font-semibold tracking-tight mb-0.5">Recent Runs</h5>
            <p className="text-xs text-muted-foreground">
              Each row is an agent session that was captured, judged, and scored.
            </p>
          </div>
          <span className="badge text-bg-secondary">{runs.length}</span>
        </div>

        {runs.length === 0 ? (
          <p className="text-sm text-muted-foreground">No runs captured yet. Runs appear here once the agent completes a session with self-evolving enabled.</p>
        ) : (
          <div className="overflow-x-auto -mx-5">
            <table className="table w-full">
              <thead>
                <tr>
                  <th title="Short identifier for this run — hover for full ID">Run</th>
                  <th title="Chat session that triggered this run">Session</th>
                  <th title="How the judge scored the run outcome">Outcome</th>
                  <th title="Judge's recommendation on whether to promote artifacts from this run">Recommendation</th>
                  <th>Started</th>
                  <th className="text-right">Action</th>
                </tr>
              </thead>
              <tbody>
                {runs.map((run) => (
                  <tr
                    key={run.id}
                    className={selectedRunId === run.id ? 'bg-primary/5' : 'cursor-pointer hover:bg-muted/40'}
                    onClick={() => onSelectRun(run.id)}
                  >
                    <td>
                      <span className="font-mono text-xs" title={run.id}>{shortId(run.id)}</span>
                    </td>
                    <td>
                      <span className="font-mono text-xs" title={run.sessionId ?? undefined}>{shortId(run.sessionId ?? 'n/a')}</span>
                    </td>
                    <td>
                      <span className={`badge ${statusBadgeClass(run.outcomeStatus)}`}>
                        {humanizeStatus(run.outcomeStatus)}
                      </span>
                    </td>
                    <td className="text-sm">{humanizeStatus(run.promotionRecommendation)}</td>
                    <td className="text-sm text-muted-foreground">{formatTimestamp(run.startedAt)}</td>
                    <td className="text-right">
                      <button
                        type="button"
                        className={`btn btn-sm ${selectedRunId === run.id ? 'btn-primary' : 'btn-secondary'}`}
                        onClick={(e) => { e.stopPropagation(); onSelectRun(run.id); }}
                      >
                        Inspect
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </div>
  );
}
