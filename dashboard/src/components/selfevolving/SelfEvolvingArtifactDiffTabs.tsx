import type { ReactElement } from 'react';
import { Card } from 'react-bootstrap';

import type {
  SelfEvolvingArtifactRevisionDiff,
  SelfEvolvingArtifactTransitionDiff,
} from '../../api/selfEvolving';
import type { SelfEvolvingArtifactCompareMode } from './SelfEvolvingArtifactCompareTypes';

interface SelfEvolvingArtifactDiffTabsProps {
  compareMode: SelfEvolvingArtifactCompareMode;
  revisionDiff: SelfEvolvingArtifactRevisionDiff | null;
  transitionDiff: SelfEvolvingArtifactTransitionDiff | null;
  isLoading: boolean;
}

export function SelfEvolvingArtifactDiffTabs({
  compareMode,
  revisionDiff,
  transitionDiff,
  isLoading,
}: SelfEvolvingArtifactDiffTabsProps): ReactElement {
  const activeSummary = compareMode === 'transition' ? transitionDiff?.summary : revisionDiff?.summary;
  const semanticSections = revisionDiff?.semanticSections ?? [];
  const rawPatch = revisionDiff?.rawPatch ?? '';

  return (
    <Card className="selfevolving-section-card">
      <Card.Body>
        <div className="mb-3">
          <h5 className="mb-1">Semantic diff</h5>
          <p className="text-body-secondary small mb-0">Evidence-aware artifact compare with semantic sections and raw patch fallback.</p>
        </div>

        {isLoading ? (
          <div className="text-body-secondary small">Loading diff...</div>
        ) : activeSummary == null ? (
          <div className="text-body-secondary small">Choose an artifact stream and compare pair to inspect changes.</div>
        ) : (
          <div className="d-flex flex-column gap-3">
            <div className="selfevolving-verdict-block">
              <div className="small text-uppercase text-body-secondary mb-1">Summary</div>
              <div>{activeSummary}</div>
            </div>
            <div className="selfevolving-verdict-block">
              <div className="small text-uppercase text-body-secondary mb-2">Semantic diff</div>
              {semanticSections.length === 0 ? (
                <div className="small text-body-secondary">No semantic sections were derived for this compare pair.</div>
              ) : (
                <ul className="mb-0 ps-3">
                  {semanticSections.map((section) => (
                    <li key={section}>{section}</li>
                  ))}
                </ul>
              )}
            </div>
            <div className="selfevolving-verdict-block">
              <div className="small text-uppercase text-body-secondary mb-2">Raw patch</div>
              <pre className="mb-0 small selfevolving-raw-patch">{rawPatch || 'No raw patch available.'}</pre>
            </div>
          </div>
        )}
      </Card.Body>
    </Card>
  );
}
