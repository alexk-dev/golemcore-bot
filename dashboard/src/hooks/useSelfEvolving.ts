import {
  type UseMutationResult,
  type UseQueryResult,
  useMutation,
  useQuery,
  useQueryClient,
} from '@tanstack/react-query';
import {
  createSelfEvolvingRegressionCampaign,
  getSelfEvolvingArtifactLineage,
  getSelfEvolvingArtifactRevisionDiff,
  getSelfEvolvingArtifactRevisionEvidence,
  getSelfEvolvingArtifacts,
  getSelfEvolvingArtifactTransitionDiff,
  getSelfEvolvingArtifactTransitionEvidence,
  getSelfEvolvingArtifactWorkspaceSummary,
  getSelfEvolvingCampaigns,
  getSelfEvolvingCandidates,
  getSelfEvolvingRun,
  getSelfEvolvingRuns,
  type SelfEvolvingArtifactCatalogEntry,
  type SelfEvolvingArtifactEvidence,
  type SelfEvolvingArtifactLineage,
  type SelfEvolvingArtifactRevisionDiff,
  type SelfEvolvingArtifactTransitionDiff,
  type SelfEvolvingArtifactWorkspaceSummary,
  planSelfEvolvingPromotion,
  type SelfEvolvingCampaign,
  type SelfEvolvingCandidate,
  type SelfEvolvingPromotionDecision,
  type SelfEvolvingRunDetail,
  type SelfEvolvingRunSummary,
} from '../api/selfEvolving';

export function useSelfEvolvingRuns(): UseQueryResult<SelfEvolvingRunSummary[], unknown> {
  return useQuery({
    queryKey: ['self-evolving', 'runs'],
    queryFn: getSelfEvolvingRuns,
  });
}

export function useSelfEvolvingRunDetail(runId: string | null): UseQueryResult<SelfEvolvingRunDetail, unknown> {
  return useQuery({
    queryKey: ['self-evolving', 'runs', runId],
    queryFn: () => getSelfEvolvingRun(runId ?? ''),
    enabled: runId != null && runId.length > 0,
  });
}

export function useSelfEvolvingCandidates(): UseQueryResult<SelfEvolvingCandidate[], unknown> {
  return useQuery({
    queryKey: ['self-evolving', 'candidates'],
    queryFn: getSelfEvolvingCandidates,
  });
}

export function useSelfEvolvingArtifacts(): UseQueryResult<SelfEvolvingArtifactCatalogEntry[], unknown> {
  return useQuery({
    queryKey: ['self-evolving', 'artifacts'],
    queryFn: getSelfEvolvingArtifacts,
  });
}

export function useSelfEvolvingArtifactWorkspaceSummary(
  artifactStreamId: string | null,
): UseQueryResult<SelfEvolvingArtifactWorkspaceSummary, unknown> {
  return useQuery({
    queryKey: ['self-evolving', 'artifacts', artifactStreamId, 'summary'],
    queryFn: () => getSelfEvolvingArtifactWorkspaceSummary(artifactStreamId ?? ''),
    enabled: artifactStreamId != null && artifactStreamId.length > 0,
  });
}

export function useSelfEvolvingArtifactLineage(
  artifactStreamId: string | null,
): UseQueryResult<SelfEvolvingArtifactLineage, unknown> {
  return useQuery({
    queryKey: ['self-evolving', 'artifacts', artifactStreamId, 'lineage'],
    queryFn: () => getSelfEvolvingArtifactLineage(artifactStreamId ?? ''),
    enabled: artifactStreamId != null && artifactStreamId.length > 0,
  });
}

export function useSelfEvolvingArtifactRevisionDiff(
  artifactStreamId: string | null,
  fromRevisionId: string | null,
  toRevisionId: string | null,
): UseQueryResult<SelfEvolvingArtifactRevisionDiff, unknown> {
  return useQuery({
    queryKey: ['self-evolving', 'artifacts', artifactStreamId, 'revision-diff', fromRevisionId, toRevisionId],
    queryFn: () => getSelfEvolvingArtifactRevisionDiff(artifactStreamId ?? '', fromRevisionId ?? '', toRevisionId ?? ''),
    enabled: artifactStreamId != null
      && artifactStreamId.length > 0
      && fromRevisionId != null
      && fromRevisionId.length > 0
      && toRevisionId != null
      && toRevisionId.length > 0,
  });
}

export function useSelfEvolvingArtifactTransitionDiff(
  artifactStreamId: string | null,
  fromNodeId: string | null,
  toNodeId: string | null,
): UseQueryResult<SelfEvolvingArtifactTransitionDiff, unknown> {
  return useQuery({
    queryKey: ['self-evolving', 'artifacts', artifactStreamId, 'transition-diff', fromNodeId, toNodeId],
    queryFn: () => getSelfEvolvingArtifactTransitionDiff(artifactStreamId ?? '', fromNodeId ?? '', toNodeId ?? ''),
    enabled: artifactStreamId != null
      && artifactStreamId.length > 0
      && fromNodeId != null
      && fromNodeId.length > 0
      && toNodeId != null
      && toNodeId.length > 0,
  });
}

export function useSelfEvolvingArtifactEvidence(
  compareMode: 'revision' | 'transition',
  artifactStreamId: string | null,
  revisionId: string | null,
  fromNodeId: string | null,
  toNodeId: string | null,
): UseQueryResult<SelfEvolvingArtifactEvidence, unknown> {
  return useQuery({
    queryKey: ['self-evolving', 'artifacts', artifactStreamId, 'evidence', compareMode, revisionId, fromNodeId, toNodeId],
    queryFn: () => compareMode === 'transition'
      ? getSelfEvolvingArtifactTransitionEvidence(artifactStreamId ?? '', fromNodeId ?? '', toNodeId ?? '')
      : getSelfEvolvingArtifactRevisionEvidence(artifactStreamId ?? '', revisionId ?? ''),
    enabled: artifactStreamId != null
      && artifactStreamId.length > 0
      && (
        (compareMode === 'revision' && revisionId != null && revisionId.length > 0)
        || (compareMode === 'transition'
          && fromNodeId != null
          && fromNodeId.length > 0
          && toNodeId != null
          && toNodeId.length > 0)
      ),
  });
}

export function useSelfEvolvingCampaigns(): UseQueryResult<SelfEvolvingCampaign[], unknown> {
  return useQuery({
    queryKey: ['self-evolving', 'campaigns'],
    queryFn: getSelfEvolvingCampaigns,
  });
}

export function usePlanSelfEvolvingPromotion(): UseMutationResult<SelfEvolvingPromotionDecision, unknown, string> {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (candidateId: string) => planSelfEvolvingPromotion(candidateId),
    onSuccess: () => Promise.all([
      qc.invalidateQueries({ queryKey: ['self-evolving', 'candidates'] }),
      qc.invalidateQueries({ queryKey: ['self-evolving', 'campaigns'] }),
      qc.invalidateQueries({ queryKey: ['self-evolving', 'artifacts'] }),
    ]),
  });
}

export function useCreateSelfEvolvingRegressionCampaign(): UseMutationResult<SelfEvolvingCampaign, unknown, string> {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (runId: string) => createSelfEvolvingRegressionCampaign(runId),
    onSuccess: () => Promise.all([
      qc.invalidateQueries({ queryKey: ['self-evolving', 'campaigns'] }),
      qc.invalidateQueries({ queryKey: ['self-evolving', 'runs'] }),
      qc.invalidateQueries({ queryKey: ['self-evolving', 'artifacts'] }),
    ]),
  });
}
