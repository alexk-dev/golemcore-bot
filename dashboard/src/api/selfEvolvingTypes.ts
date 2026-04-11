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
