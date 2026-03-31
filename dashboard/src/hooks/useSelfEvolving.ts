import {
  type UseMutationResult,
  type UseQueryResult,
  useMutation,
  useQuery,
  useQueryClient,
} from '@tanstack/react-query';
import {
  createSelfEvolvingRegressionCampaign,
  getSelfEvolvingCampaigns,
  getSelfEvolvingCandidates,
  getSelfEvolvingRun,
  getSelfEvolvingRuns,
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
    ]),
  });
}
