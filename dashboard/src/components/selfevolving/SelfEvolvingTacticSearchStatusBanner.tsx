import type { ReactElement } from 'react';

import type { SelfEvolvingTacticSearchStatus } from '../../api/selfEvolving';

interface Props {
  status: SelfEvolvingTacticSearchStatus | null;
}

const MODE_LABELS: Record<string, string> = {
  hybrid: 'Hybrid',
  bm25: 'BM25-only',
};

export function SelfEvolvingTacticSearchStatusBanner({ status }: Props): ReactElement {
  const mode = status?.mode ?? 'hybrid';
  const label = MODE_LABELS[mode] ?? mode;
  const isDegraded = status?.degraded === true;

  return (
    <div
      className={`rounded-xl border px-4 py-2.5 text-sm mb-3 ${
        isDegraded
          ? 'border-orange-300/40 bg-orange-50/60 text-orange-800 dark:border-orange-500/30 dark:bg-orange-950/30 dark:text-orange-300'
          : 'border-primary/20 bg-primary/5 text-foreground'
      }`}
      title={isDegraded
        ? 'Embedding search is degraded — results are based on keyword matching only'
        : 'Search uses both keyword matching and semantic embeddings'}
    >
      <strong>{label}</strong>
      {status?.reason != null && ` \u00b7 ${status.reason}`}
    </div>
  );
}
