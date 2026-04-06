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

export interface SelfEvolvingCandidateEvidenceRef {
  traceId: string | null;
  spanId: string | null;
  outputFragment: string | null;
}

export interface SelfEvolvingCandidateProposal {
  summary: string | null;
  rationale: string | null;
  behaviorInstructions: string | null;
  toolInstructions: string | null;
  expectedOutcome: string | null;
  approvalNotes: string | null;
  proposedPatch: string | null;
  riskLevel: string | null;
}

export interface SelfEvolvingCandidate {
  id: string;
  goal: string | null;
  artifactType: string | null;
  artifactStreamId?: string | null;
  artifactKey?: string | null;
  status: string | null;
  riskLevel: string | null;
  expectedImpact: string | null;
  proposedDiff: string | null;
  proposal?: SelfEvolvingCandidateProposal | null;
  sourceRunIds: string[];
  evidenceRefs: SelfEvolvingCandidateEvidenceRef[];
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

export interface SelfEvolvingArtifactCatalogEntry {
  artifactStreamId: string;
  originArtifactStreamId: string | null;
  artifactKey: string | null;
  artifactAliases: string[];
  artifactType: string | null;
  artifactSubtype: string | null;
  displayName: string | null;
  latestRevisionId: string | null;
  activeRevisionId: string | null;
  latestCandidateRevisionId: string | null;
  currentLifecycleState: string | null;
  currentRolloutStage: string | null;
  hasRegression: boolean | null;
  hasPendingApproval: boolean | null;
  campaignCount: number | null;
  projectionSchemaVersion: number | null;
  updatedAt: string | null;
  projectedAt: string | null;
}

export interface SelfEvolvingArtifactCompareOption {
  label: string;
  fromId: string;
  toId: string;
}

export interface SelfEvolvingArtifactCompareOptions {
  artifactStreamId: string;
  defaultFromRevisionId: string | null;
  defaultToRevisionId: string | null;
  defaultFromNodeId: string | null;
  defaultToNodeId: string | null;
  revisionOptions: SelfEvolvingArtifactCompareOption[];
  transitionOptions: SelfEvolvingArtifactCompareOption[];
}

export interface SelfEvolvingArtifactWorkspaceSummary {
  artifactStreamId: string;
  originArtifactStreamId: string | null;
  artifactKey: string | null;
  artifactAliases: string[];
  artifactType: string | null;
  artifactSubtype: string | null;
  activeRevisionId: string | null;
  latestCandidateRevisionId: string | null;
  currentLifecycleState: string | null;
  currentRolloutStage: string | null;
  campaignCount: number | null;
  projectionSchemaVersion: number | null;
  updatedAt: string | null;
  projectedAt: string | null;
  compareOptions: SelfEvolvingArtifactCompareOptions | null;
}

export interface SelfEvolvingArtifactLineageNode {
  nodeId: string;
  contentRevisionId: string | null;
  lifecycleState: string | null;
  rolloutStage: string | null;
  promotionDecisionId: string | null;
  originBundleId: string | null;
  sourceRunIds: string[];
  campaignIds: string[];
  attributionMode: string | null;
  createdAt: string | null;
}

export interface SelfEvolvingArtifactLineageEdge {
  edgeId: string;
  fromNodeId: string;
  toNodeId: string;
  edgeType: string | null;
  createdAt: string | null;
}

export interface SelfEvolvingArtifactLineage {
  artifactStreamId: string;
  originArtifactStreamId: string | null;
  artifactKey: string | null;
  nodes: SelfEvolvingArtifactLineageNode[];
  edges: SelfEvolvingArtifactLineageEdge[];
  railOrder: string[];
  branches: string[];
  defaultSelectedNodeId: string | null;
  defaultSelectedRevisionId: string | null;
  projectionSchemaVersion: number | null;
  projectedAt: string | null;
}

export interface SelfEvolvingArtifactImpactSummary {
  attributionMode: string | null;
  campaignDelta: number | null;
  regressionIntroduced: boolean | null;
  verdictDelta: number | null;
  latencyDeltaMs: number | null;
  costDeltaMicros: number | null;
  projectionSchemaVersion: number | null;
  projectedAt: string | null;
}

export interface SelfEvolvingArtifactRevisionDiff {
  artifactStreamId: string;
  artifactKey: string | null;
  fromRevisionId: string | null;
  toRevisionId: string | null;
  summary: string | null;
  semanticSections: string[];
  rawPatch: string | null;
  changedFields: string[];
  riskSignals: string[];
  impactSummary: SelfEvolvingArtifactImpactSummary | null;
  attributionMode: string | null;
  projectionSchemaVersion: number | null;
  projectedAt: string | null;
}

export interface SelfEvolvingArtifactTransitionDiff {
  artifactStreamId: string;
  artifactKey: string | null;
  fromNodeId: string | null;
  toNodeId: string | null;
  fromRevisionId: string | null;
  toRevisionId: string | null;
  fromRolloutStage: string | null;
  toRolloutStage: string | null;
  contentChanged: boolean;
  summary: string | null;
  impactSummary: SelfEvolvingArtifactImpactSummary | null;
  attributionMode: string | null;
  projectionSchemaVersion: number | null;
  projectedAt: string | null;
}

export interface SelfEvolvingArtifactEvidence {
  artifactStreamId: string;
  artifactKey: string | null;
  payloadKind: 'revision' | 'compare' | 'transition';
  revisionId: string | null;
  fromRevisionId: string | null;
  toRevisionId: string | null;
  fromNodeId: string | null;
  toNodeId: string | null;
  runIds: string[];
  traceIds: string[];
  spanIds: string[];
  campaignIds: string[];
  promotionDecisionIds: string[];
  approvalRequestIds: string[];
  findings: string[];
  projectionSchemaVersion: number | null;
  projectedAt: string | null;
}

export interface SelfEvolvingTacticSearchStatus {
  mode: string | null;
  reason: string | null;
  provider: string | null;
  model: string | null;
  degraded: boolean | null;
  runtimeState?: string | null;
  owned?: boolean | null;
  runtimeInstalled: boolean | null;
  runtimeHealthy: boolean | null;
  runtimeVersion: string | null;
  baseUrl: string | null;
  modelAvailable: boolean | null;
  restartAttempts?: number | null;
  nextRetryAt?: string | null;
  nextRetryTime?: string | null;
  autoInstallConfigured: boolean | null;
  pullOnStartConfigured: boolean | null;
  pullAttempted: boolean | null;
  pullSucceeded: boolean | null;
  updatedAt: string | null;
}

export interface SelfEvolvingTacticSearchStatusPreview {
  provider: string | null;
  model: string | null;
  baseUrl: string | null;
}

export interface SelfEvolvingTacticSearchExplanation {
  searchMode: string | null;
  degradedReason: string | null;
  bm25Score: number | null;
  vectorScore: number | null;
  rrfScore: number | null;
  qualityPrior: number | null;
  mmrDiversityAdjustment: number | null;
  negativeMemoryPenalty: number | null;
  personalizationBoost: number | null;
  matchedQueryViews: string[];
  matchedTerms: string[];
  eligible: boolean | null;
  gatingReason: string | null;
  finalScore: number | null;
}

export interface SelfEvolvingTactic {
  tacticId: string;
  artifactStreamId: string | null;
  originArtifactStreamId: string | null;
  artifactKey: string | null;
  artifactType: string | null;
  title: string | null;
  aliases: string[];
  contentRevisionId: string | null;
  intentSummary: string | null;
  behaviorSummary: string | null;
  toolSummary: string | null;
  outcomeSummary: string | null;
  benchmarkSummary: string | null;
  approvalNotes: string | null;
  evidenceSnippets: string[];
  taskFamilies: string[];
  tags: string[];
  promotionState: string | null;
  rolloutStage: string | null;
  successRate: number | null;
  benchmarkWinRate: number | null;
  regressionFlags: string[];
  recencyScore: number | null;
  golemLocalUsageSuccess: number | null;
  embeddingStatus: string | null;
  updatedAt: string | null;
}

export interface SelfEvolvingTacticSearchResult extends SelfEvolvingTactic {
  score: number | null;
  explanation: SelfEvolvingTacticSearchExplanation | null;
}

export interface SelfEvolvingTacticSearchResponse {
  query: string | null;
  status: SelfEvolvingTacticSearchStatus | null;
  results: SelfEvolvingTacticSearchResult[];
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

export async function getSelfEvolvingTacticSearchStatus(
  preview?: SelfEvolvingTacticSearchStatusPreview | null,
): Promise<SelfEvolvingTacticSearchStatus> {
  const params = preview == null ? undefined : {
    provider: preview.provider ?? undefined,
    model: preview.model ?? undefined,
    baseUrl: preview.baseUrl ?? undefined,
  };
  const { data } = await client.get<SelfEvolvingTacticSearchStatus>('/self-evolving/tactics/status', {
    params,
  });
  return data;
}

export async function installSelfEvolvingTacticEmbeddingModel(model: string): Promise<SelfEvolvingTacticSearchStatus> {
  const { data } = await client.post<SelfEvolvingTacticSearchStatus>('/self-evolving/tactics/install', { model });
  return data;
}

export interface SelfEvolvingRemoteEmbeddingProbeRequest {
  baseUrl: string | null;
  apiKey: string | null;
  model: string | null;
  dimensions: number | null;
  timeoutMs: number | null;
}

export interface SelfEvolvingRemoteEmbeddingProbeResponse {
  ok: boolean;
  model: string | null;
  dimensions: number | null;
  vectorLength: number | null;
  baseUrl: string | null;
  error: string | null;
}

export async function probeSelfEvolvingRemoteEmbedding(
  request: SelfEvolvingRemoteEmbeddingProbeRequest,
): Promise<SelfEvolvingRemoteEmbeddingProbeResponse> {
  const { data } = await client.post<SelfEvolvingRemoteEmbeddingProbeResponse>(
    '/self-evolving/tactics/embeddings/probe',
    request,
  );
  return data;
}

export async function getSelfEvolvingArtifacts(): Promise<SelfEvolvingArtifactCatalogEntry[]> {
  const { data } = await client.get<SelfEvolvingArtifactCatalogEntry[]>('/self-evolving/artifacts');
  return data;
}

export async function getSelfEvolvingArtifactWorkspaceSummary(
  artifactStreamId: string,
): Promise<SelfEvolvingArtifactWorkspaceSummary> {
  const { data } = await client.get<SelfEvolvingArtifactWorkspaceSummary>(`/self-evolving/artifacts/${artifactStreamId}`);
  return data;
}

export async function getSelfEvolvingArtifactLineage(artifactStreamId: string): Promise<SelfEvolvingArtifactLineage> {
  const { data } = await client.get<SelfEvolvingArtifactLineage>(`/self-evolving/artifacts/${artifactStreamId}/lineage`);
  return data;
}

export async function getSelfEvolvingArtifactRevisionDiff(
  artifactStreamId: string,
  fromRevisionId: string,
  toRevisionId: string,
): Promise<SelfEvolvingArtifactRevisionDiff> {
  const { data } = await client.get<SelfEvolvingArtifactRevisionDiff>(`/self-evolving/artifacts/${artifactStreamId}/diff`, {
    params: { fromRevisionId, toRevisionId },
  });
  return data;
}

export async function getSelfEvolvingArtifactTransitionDiff(
  artifactStreamId: string,
  fromNodeId: string,
  toNodeId: string,
): Promise<SelfEvolvingArtifactTransitionDiff> {
  const { data } = await client.get<SelfEvolvingArtifactTransitionDiff>(
    `/self-evolving/artifacts/${artifactStreamId}/transition-diff`,
    { params: { fromNodeId, toNodeId } },
  );
  return data;
}

export async function getSelfEvolvingArtifactRevisionEvidence(
  artifactStreamId: string,
  revisionId: string,
): Promise<SelfEvolvingArtifactEvidence> {
  const { data } = await client.get<SelfEvolvingArtifactEvidence>(`/self-evolving/artifacts/${artifactStreamId}/evidence`, {
    params: { revisionId },
  });
  return data;
}

export async function getSelfEvolvingArtifactCompareEvidence(
  artifactStreamId: string,
  fromRevisionId: string,
  toRevisionId: string,
): Promise<SelfEvolvingArtifactEvidence> {
  const { data } = await client.get<SelfEvolvingArtifactEvidence>(`/self-evolving/artifacts/${artifactStreamId}/compare-evidence`, {
    params: { fromRevisionId, toRevisionId },
  });
  return data;
}

export async function getSelfEvolvingArtifactTransitionEvidence(
  artifactStreamId: string,
  fromNodeId: string,
  toNodeId: string,
): Promise<SelfEvolvingArtifactEvidence> {
  const { data } = await client.get<SelfEvolvingArtifactEvidence>(
    `/self-evolving/artifacts/${artifactStreamId}/transition-evidence`,
    { params: { fromNodeId, toNodeId } },
  );
  return data;
}

export async function planSelfEvolvingPromotion(candidateId: string): Promise<SelfEvolvingPromotionDecision> {
  const { data } = await client.post<SelfEvolvingPromotionDecision>(`/self-evolving/candidates/${candidateId}/promotion`);
  return data;
}

export async function deactivateSelfEvolvingTactic(tacticId: string): Promise<void> {
  await client.post(`/self-evolving/tactics/${tacticId}/deactivate`);
}

export async function reactivateSelfEvolvingTactic(tacticId: string): Promise<void> {
  await client.post(`/self-evolving/tactics/${tacticId}/reactivate`);
}

export async function deleteSelfEvolvingTactic(tacticId: string): Promise<void> {
  await client.delete(`/self-evolving/tactics/${tacticId}`);
}

export async function getSelfEvolvingCampaigns(): Promise<SelfEvolvingCampaign[]> {
  const { data } = await client.get<SelfEvolvingCampaign[]>('/self-evolving/benchmarks/campaigns');
  return data;
}

export async function searchSelfEvolvingTactics(query: string): Promise<SelfEvolvingTacticSearchResponse> {
  const { data } = await client.get<SelfEvolvingTacticSearchResponse>('/self-evolving/tactics/search', {
    params: { q: query },
  });
  return data;
}

export async function createSelfEvolvingRegressionCampaign(runId: string): Promise<SelfEvolvingCampaign> {
  const { data } = await client.post<SelfEvolvingCampaign>(`/self-evolving/benchmarks/regression/${runId}`);
  return data;
}
