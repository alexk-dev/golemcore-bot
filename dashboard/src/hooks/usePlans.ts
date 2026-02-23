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
import { extractErrorMessage } from '../utils/extractErrorMessage';

const PLAN_CONTROL_QUERY_KEY = 'plan-control-state';

export interface EnablePlanModePayload {
  sessionId: string;
  modelTier?: string | null;
}

export interface PlanSessionActionPayload {
  planId: string;
  sessionId: string;
}

interface PlanMutationConfig<TVariables> {
  mutationFn: (variables: TVariables) => Promise<PlanControlState>;
  successMessage: string;
  errorPrefix: string;
  resolveSessionId?: (variables: TVariables, data: PlanControlState) => string | null;
}

function buildPlanControlQueryKey(sessionId: string): readonly [string, string] {
  return [PLAN_CONTROL_QUERY_KEY, sessionId] as const;
}

export function usePlanControlState(sessionId: string, enabled = true): UseQueryResult<PlanControlState, unknown> {
  return useQuery({
    queryKey: buildPlanControlQueryKey(sessionId),
    queryFn: () => getPlanControlState(sessionId),
    enabled: enabled && sessionId.trim().length > 0,
    refetchInterval: enabled ? 15000 : false,
  });
}

export function useEnablePlanMode(): UseMutationResult<PlanControlState, unknown, EnablePlanModePayload> {
  return usePlanMutation<EnablePlanModePayload>({
    mutationFn: enablePlanMode,
    successMessage: 'Plan mode enabled',
    errorPrefix: 'Failed to enable plan mode',
    resolveSessionId: (variables) => variables.sessionId,
  });
}

export function useDisablePlanMode(): UseMutationResult<PlanControlState, unknown, string> {
  return usePlanMutation<string>({
    mutationFn: disablePlanMode,
    successMessage: 'Plan mode disabled',
    errorPrefix: 'Failed to disable plan mode',
    resolveSessionId: (sessionId) => sessionId,
  });
}

export function useDonePlanMode(): UseMutationResult<PlanControlState, unknown, string> {
  return usePlanMutation<string>({
    mutationFn: donePlanMode,
    successMessage: 'Plan mode done',
    errorPrefix: 'Failed to finish plan mode',
    resolveSessionId: (sessionId) => sessionId,
  });
}

export function useApprovePlan(): UseMutationResult<PlanControlState, unknown, PlanSessionActionPayload> {
  return usePlanMutation<PlanSessionActionPayload>({
    mutationFn: (variables) => approvePlan(variables.planId, variables.sessionId),
    successMessage: 'Plan approved and execution started',
    errorPrefix: 'Failed to approve plan',
    resolveSessionId: (variables) => variables.sessionId,
  });
}

export function useCancelPlan(): UseMutationResult<PlanControlState, unknown, PlanSessionActionPayload> {
  return usePlanMutation<PlanSessionActionPayload>({
    mutationFn: (variables) => cancelPlan(variables.planId, variables.sessionId),
    successMessage: 'Plan cancelled',
    errorPrefix: 'Failed to cancel plan',
    resolveSessionId: (variables) => variables.sessionId,
  });
}

export function useResumePlan(): UseMutationResult<PlanControlState, unknown, PlanSessionActionPayload> {
  return usePlanMutation<PlanSessionActionPayload>({
    mutationFn: (variables) => resumePlan(variables.planId, variables.sessionId),
    successMessage: 'Plan resume requested',
    errorPrefix: 'Failed to resume plan',
    resolveSessionId: (variables) => variables.sessionId,
  });
}

export function usePlanActionsPending(actions: Array<{ isPending?: boolean }>): boolean {
  return useMemo(() => actions.some((action) => action.isPending === true), [actions]);
}

function usePlanMutation<TVariables>(
  config: PlanMutationConfig<TVariables>,
): UseMutationResult<PlanControlState, unknown, TVariables> {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: config.mutationFn,
    onSuccess: async (data, variables) => {
      toast.success(config.successMessage);
      const resolvedSessionId = config.resolveSessionId?.(variables, data) ?? data.sessionId;
      if (resolvedSessionId != null && resolvedSessionId.trim().length > 0) {
        await queryClient.invalidateQueries({ queryKey: buildPlanControlQueryKey(resolvedSessionId) });
      } else {
        await queryClient.invalidateQueries({ queryKey: [PLAN_CONTROL_QUERY_KEY] });
      }
    },
    onError: (error) => {
      toast.error(`${config.errorPrefix}: ${extractErrorMessage(error)}`);
    },
  });
}
