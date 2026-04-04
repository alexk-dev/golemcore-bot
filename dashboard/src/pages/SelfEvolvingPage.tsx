import { type ReactElement, useDeferredValue, useEffect, useRef, useState } from 'react';
import { Alert, Col, Row } from 'react-bootstrap';
import toast from 'react-hot-toast';

import { SelfEvolvingCandidateQueue } from '../components/selfevolving/SelfEvolvingCandidateQueue';
import { SelfEvolvingOverviewCards } from '../components/selfevolving/SelfEvolvingOverviewCards';
import { SelfEvolvingRunTable } from '../components/selfevolving/SelfEvolvingRunTable';
import { SelfEvolvingTacticSearchWorkspace } from '../components/selfevolving/SelfEvolvingTacticSearchWorkspace';
import { SelfEvolvingVerdictPanel } from '../components/selfevolving/SelfEvolvingVerdictPanel';
import {
  usePlanSelfEvolvingPromotion,
  useSelfEvolvingCandidates,
  useSelfEvolvingRunDetail,
  useSelfEvolvingRuns,
  useSelfEvolvingTacticSearch,
} from '../hooks/useSelfEvolving';

type ActiveTab = 'runs' | 'candidates' | 'tactics';

export default function SelfEvolvingPage(): ReactElement {
  const [activeTab, setActiveTab] = useState<ActiveTab>('runs');
  const [selectedRunId, setSelectedRunId] = useState<string | null>(null);
  const [selectedCandidateId, setSelectedCandidateId] = useState<string | null>(null);
  const [tacticQuery, setTacticQuery] = useState<string>('');
  const [selectedTacticId, setSelectedTacticId] = useState<string | null>(null);
  const verdictPanelRef = useRef<HTMLDivElement | null>(null);
  const deferredTacticQuery = useDeferredValue(tacticQuery);
  const planPromotion = usePlanSelfEvolvingPromotion();

  const runsQuery = useSelfEvolvingRuns();
  const candidatesQuery = useSelfEvolvingCandidates();
  const tacticSearchQuery = useSelfEvolvingTacticSearch(deferredTacticQuery);

  const runs = runsQuery.data ?? [];
  const candidates = candidatesQuery.data ?? [];
  const activeRunId = selectedRunId ?? runs[0]?.id ?? null;
  const activeCandidateId = selectedCandidateId ?? candidates[0]?.id ?? null;
  const activeRunQuery = useSelfEvolvingRunDetail(activeRunId);

  const hasError = runsQuery.isError || candidatesQuery.isError;

  const handlePlanPromotion = async (candidateId: string): Promise<void> => {
    await planPromotion.mutateAsync(candidateId);
    toast.success('Promotion decision planned');
  };

  // Scroll verdict panel into view when a run is explicitly selected.
  useEffect(() => {
    if (selectedRunId == null) {
      return;
    }
    verdictPanelRef.current?.scrollIntoView({ behavior: 'smooth', block: 'start' });
  }, [selectedRunId]);

  return (
    <div>
      <div className="section-header d-flex align-items-center justify-content-between mb-3">
        <div>
          <h4 className="mb-1">Self-Evolving</h4>
          <p className="text-body-secondary mb-0">
            Inspect runs, review candidates, and search tactics.
          </p>
        </div>
      </div>

      {hasError && (
        <Alert variant="danger" className="mb-4">
          Failed to load Self-Evolving data. Refresh the page and check backend connectivity.
        </Alert>
      )}

      <SelfEvolvingOverviewCards runs={runs} candidates={candidates} />

      <ul className="nav nav-tabs mb-3">
        <li className="nav-item">
          <button
            type="button"
            className={`nav-link ${activeTab === 'runs' ? 'active' : ''}`}
            onClick={() => setActiveTab('runs')}
          >
            Runs
          </button>
        </li>
        <li className="nav-item">
          <button
            type="button"
            className={`nav-link ${activeTab === 'candidates' ? 'active' : ''}`}
            onClick={() => setActiveTab('candidates')}
          >
            Candidates
          </button>
        </li>
        <li className="nav-item">
          <button
            type="button"
            className={`nav-link ${activeTab === 'tactics' ? 'active' : ''}`}
            onClick={() => setActiveTab('tactics')}
          >
            Tactics
          </button>
        </li>
      </ul>

      {activeTab === 'runs' && (
        <Row className="g-3">
          <Col xl={7}>
            <SelfEvolvingRunTable
              runs={runs}
              selectedRunId={activeRunId}
              onSelectRun={setSelectedRunId}
            />
          </Col>
          <Col xl={5}>
            <div ref={verdictPanelRef}>
              <SelfEvolvingVerdictPanel
                run={activeRunQuery.data}
                isLoading={activeRunQuery.isLoading}
              />
            </div>
          </Col>
        </Row>
      )}

      {activeTab === 'candidates' && (
        <SelfEvolvingCandidateQueue
          candidates={candidates}
          selectedCandidateId={activeCandidateId}
          promotingCandidateId={planPromotion.variables ?? null}
          onSelectCandidate={setSelectedCandidateId}
          onSelectRun={(runId) => {
            setSelectedRunId(runId);
            setActiveTab('runs');
          }}
          onPlanPromotion={(candidateId) => { void handlePlanPromotion(candidateId); }}
        />
      )}

      {activeTab === 'tactics' && (
        <SelfEvolvingTacticSearchWorkspace
          query={tacticQuery}
          onQueryChange={(nextQuery) => {
            setTacticQuery(nextQuery);
            setSelectedTacticId(null);
          }}
          searchResponse={tacticSearchQuery.data ?? null}
          selectedTacticId={selectedTacticId}
          onSelectTacticId={setSelectedTacticId}
          onBackToResults={() => setSelectedTacticId(null)}
        />
      )}
    </div>
  );
}
