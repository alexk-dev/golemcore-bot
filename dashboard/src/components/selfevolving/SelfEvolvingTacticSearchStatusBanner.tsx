import type { ReactElement } from 'react';
import { Alert } from 'react-bootstrap';

import type { SelfEvolvingTacticSearchStatus } from '../../api/selfEvolving';

interface Props {
  status: SelfEvolvingTacticSearchStatus | null;
}

export function SelfEvolvingTacticSearchStatusBanner({ status }: Props): ReactElement {
  const mode = status?.mode ?? 'hybrid';
  const label = mode === 'hybrid' ? 'Hybrid' : mode === 'bm25' ? 'BM25-only' : mode;
  return (
    <Alert variant={status?.degraded ? 'warning' : 'info'} className="mb-3">
      <strong>{label}</strong>
      {status?.reason ? ` · ${status.reason}` : ''}
    </Alert>
  );
}
