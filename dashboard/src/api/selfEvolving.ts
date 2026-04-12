import client from './client';
import type {
  SelfEvolvingRunSummary,
  SelfEvolvingRunDetail,
  SelfEvolvingCandidate,
  SelfEvolvingCampaign,
  SelfEvolvingPromotionDecision,
  SelfEvolvingArtifactCatalogEntry,
  SelfEvolvingArtifactWorkspaceSummary,
  SelfEvolvingArtifactLineage,
  SelfEvolvingArtifactRevisionDiff,
  SelfEvolvingArtifactTransitionDiff,
  SelfEvolvingArtifactEvidence,
  SelfEvolvingTacticSearchStatus,
  SelfEvolvingTacticSearchStatusPreview,
  SelfEvolvingTacticSearchResponse,
} from './selfEvolvingTypes';

export type {
  SelfEvolvingRunSummary,
  SelfEvolvingRunDetailVerdict,
  SelfEvolvingRunDetail,
  SelfEvolvingCandidateEvidenceRef,
  SelfEvolvingCandidateProposal,
  SelfEvolvingCandidate,
  SelfEvolvingCampaign,
  SelfEvolvingPromotionDecision,
  SelfEvolvingArtifactCatalogEntry,
  SelfEvolvingArtifactCompareOption,
  SelfEvolvingArtifactCompareOptions,
  SelfEvolvingArtifactWorkspaceSummary,
  SelfEvolvingArtifactLineageNode,
  SelfEvolvingArtifactLineageEdge,
  SelfEvolvingArtifactLineage,
  SelfEvolvingArtifactImpactSummary,
  SelfEvolvingArtifactRevisionDiff,
  SelfEvolvingArtifactTransitionDiff,
  SelfEvolvingArtifactEvidence,
  SelfEvolvingTacticSearchStatus,
  SelfEvolvingTacticSearchStatusPreview,
  SelfEvolvingTacticSearchExplanation,
  SelfEvolvingTactic,
  SelfEvolvingTacticSearchResult,
  SelfEvolvingTacticSearchResponse,
} from './selfEvolvingTypes';

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
