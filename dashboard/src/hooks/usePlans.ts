import { useMemo } from 'react';
import toast from 'react-hot-toast';
import {
  useMutation,
  useQuery,
  useQueryClient,
  type UseMutationResult,
  type UseQueryResult,
} from '@tanstack/react-query';
import {
  approvePlan,
  cancelPlan,
  disablePlanMode,
  donePlanMode,
  enablePlanMode,
  getPlanControlState,
  resumePlan,
  type PlanControlState,
} from '../api/plans';

export function usePlanControlState(enabled = true): UseQueryResult<PlanControlState, unknown> {
  return useQuery({
    queryKey: ['plan-control-state'],
    queryFn: getPlanControlState,
    enabled,
    refetchInterval: enabled ? 15000 : false,
  });
}

export function useEnablePlanMode(): UseMutationResult<PlanControlState, unknown, { chatId: string; modelTier?: string | null }> {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (payload: { chatId: string; modelTier?: string | null }) => enablePlanMode(payload),
    onSuccess: () => {
      toast.success('Plan mode enabled');
      qc.invalidateQueries({ queryKey: ['plan-control-state'] });
    },
    onError: (err) => {
      toast.error(`Failed to enable plan mode: ${extractErrorMessage(err)}`);
    },
  });
}

export function useDisablePlanMode(): UseMutationResult<PlanControlState, unknown, void> {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: () => disablePlanMode(),
    onSuccess: () => {
      toast.success('Plan mode disabled');
      qc.invalidateQueries({ queryKey: ['plan-control-state'] });
    },
    onError: (err) => {
      toast.error(`Failed to disable plan mode: ${extractErrorMessage(err)}`);
    },
  });
}

export function useDonePlanMode(): UseMutationResult<PlanControlState, unknown, void> {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: () => donePlanMode(),
    onSuccess: () => {
      toast.success('Plan mode done');
      qc.invalidateQueries({ queryKey: ['plan-control-state'] });
    },
    onError: (err) => {
      toast.error(`Failed to finish plan mode: ${extractErrorMessage(err)}`);
    },
  });
}

export function useApprovePlan(): UseMutationResult<PlanControlState, unknown, string> {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (planId: string) => approvePlan(planId),
    onSuccess: () => {
      toast.success('Plan approved and execution started');
      qc.invalidateQueries({ queryKey: ['plan-control-state'] });
    },
    onError: (err) => {
      toast.error(`Failed to approve plan: ${extractErrorMessage(err)}`);
    },
  });
}

export function useCancelPlan(): UseMutationResult<PlanControlState, unknown, string> {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (planId: string) => cancelPlan(planId),
    onSuccess: () => {
      toast.success('Plan cancelled');
      qc.invalidateQueries({ queryKey: ['plan-control-state'] });
    },
    onError: (err) => {
      toast.error(`Failed to cancel plan: ${extractErrorMessage(err)}`);
    },
  });
}

export function useResumePlan(): UseMutationResult<PlanControlState, unknown, string> {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (planId: string) => resumePlan(planId),
    onSuccess: () => {
      toast.success('Plan resume requested');
      qc.invalidateQueries({ queryKey: ['plan-control-state'] });
    },
    onError: (err) => {
      toast.error(`Failed to resume plan: ${extractErrorMessage(err)}`);
    },
  });
}

export function usePlanActionsPending(actions: Array<{ isPending?: boolean }>): boolean {
  return useMemo(() => actions.some((a) => a.isPending === true), [actions]);
}

function extractErrorMessage(err: unknown): string {
  if (typeof err === 'object' && err !== null && 'response' in err) {
    const maybeResponse = (err as { response?: { data?: unknown } }).response;
    const data = maybeResponse?.data;
    if (typeof data === 'object' && data !== null && 'message' in data) {
      const msg = (data as { message?: unknown }).message;
      if (typeof msg === 'string' && msg.trim().length > 0) {
        return msg;
      }
    }
  }
  if (err instanceof Error && err.message.trim().length > 0) {
    return err.message;
  }
  return 'Unknown error';
}
