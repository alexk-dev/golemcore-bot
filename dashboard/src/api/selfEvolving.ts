import client from './client';

export interface SelfEvolvingRunSummary {
  id: string;
  golemId: string | null;
  sessionId: string | null;
  traceId: string | null;
  artifactBundleId: string | null;
  status: string | null;
  outcomeStatus: string | null;
  promotionRecommendation: string | null;
  startedAt: string | null;
  completedAt: string | null;
}

export interface SelfEvolvingRunDetailVerdict {
  outcomeStatus: string | null;
  processStatus: string | null;
  outcomeSummary: string | null;
  processSummary: string | null;
  promotionRecommendation: string | null;
  confidence: number | null;
  processFindings: string[];
}

export interface SelfEvolvingRunDetail {
  id: string;
  golemId: string | null;
  sessionId: string | null;
  traceId: string | null;
  artifactBundleId: string | null;
  artifactBundleStatus: string | null;
  status: string | null;
  startedAt: string | null;
  completedAt: string | null;
  verdict: SelfEvolvingRunDetailVerdict | null;
}

export interface SelfEvolvingCandidate {
  id: string;
  goal: string | null;
  artifactType: string | null;
  status: string | null;
  riskLevel: string | null;
  expectedImpact: string | null;
  sourceRunIds: string[];
}

export interface SelfEvolvingCampaign {
  id: string;
  suiteId: string | null;
  baselineBundleId: string | null;
  candidateBundleId: string | null;
  status: string | null;
  startedAt: string | null;
  completedAt: string | null;
  runIds: string[];
}

export interface SelfEvolvingPromotionDecision {
  id: string;
  candidateId: string | null;
  bundleId: string | null;
  state: string | null;
  fromState: string | null;
  toState: string | null;
  mode: string | null;
  approvalRequestId: string | null;
  actorId: string | null;
  reason: string | null;
  decidedAt: string | null;
}

export async function getSelfEvolvingRuns(): Promise<SelfEvolvingRunSummary[]> {
  const { data } = await client.get<SelfEvolvingRunSummary[]>('/self-evolving/runs');
  return data;
}

export async function getSelfEvolvingRun(runId: string): Promise<SelfEvolvingRunDetail> {
  const { data } = await client.get<SelfEvolvingRunDetail>(`/self-evolving/runs/${runId}`);
  return data;
}

export async function getSelfEvolvingCandidates(): Promise<SelfEvolvingCandidate[]> {
  const { data } = await client.get<SelfEvolvingCandidate[]>('/self-evolving/candidates');
  return data;
}

export async function planSelfEvolvingPromotion(candidateId: string): Promise<SelfEvolvingPromotionDecision> {
  const { data } = await client.post<SelfEvolvingPromotionDecision>(`/self-evolving/candidates/${candidateId}/promotion`);
  return data;
}

export async function getSelfEvolvingCampaigns(): Promise<SelfEvolvingCampaign[]> {
  const { data } = await client.get<SelfEvolvingCampaign[]>('/self-evolving/benchmarks/campaigns');
  return data;
}

export async function createSelfEvolvingRegressionCampaign(runId: string): Promise<SelfEvolvingCampaign> {
  const { data } = await client.post<SelfEvolvingCampaign>(`/self-evolving/benchmarks/regression/${runId}`);
  return data;
}
