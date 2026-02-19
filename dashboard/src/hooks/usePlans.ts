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

const PLAN_CONTROL_QUERY_KEY = ['plan-control-state'] as const;

export interface EnablePlanModePayload {
  chatId: string;
  modelTier?: string | null;
}

interface PlanMutationConfig<TVariables> {
  mutationFn: (variables: TVariables) => Promise<PlanControlState>;
  successMessage: string;
  errorPrefix: string;
}

interface NoArgPlanMutationResult
  extends Omit<UseMutationResult<PlanControlState, unknown, undefined>, 'mutate' | 'mutateAsync'> {
  mutate: () => void;
  mutateAsync: () => Promise<PlanControlState>;
}

export function usePlanControlState(enabled = true): UseQueryResult<PlanControlState, unknown> {
  return useQuery({
    queryKey: PLAN_CONTROL_QUERY_KEY,
    queryFn: getPlanControlState,
    enabled,
    refetchInterval: enabled ? 15000 : false,
  });
}

export function useEnablePlanMode(): UseMutationResult<PlanControlState, unknown, EnablePlanModePayload> {
  return usePlanMutation<EnablePlanModePayload>({
    mutationFn: enablePlanMode,
    successMessage: 'Plan mode enabled',
    errorPrefix: 'Failed to enable plan mode',
  });
}

export function useDisablePlanMode(): NoArgPlanMutationResult {
  const mutation = usePlanMutation<undefined>({
    mutationFn: () => disablePlanMode(),
    successMessage: 'Plan mode disabled',
    errorPrefix: 'Failed to disable plan mode',
  });
  return toNoArgMutationResult(mutation);
}

export function useDonePlanMode(): NoArgPlanMutationResult {
  const mutation = usePlanMutation<undefined>({
    mutationFn: () => donePlanMode(),
    successMessage: 'Plan mode done',
    errorPrefix: 'Failed to finish plan mode',
  });
  return toNoArgMutationResult(mutation);
}

export function useApprovePlan(): UseMutationResult<PlanControlState, unknown, string> {
  return usePlanMutation<string>({
    mutationFn: approvePlan,
    successMessage: 'Plan approved and execution started',
    errorPrefix: 'Failed to approve plan',
  });
}

export function useCancelPlan(): UseMutationResult<PlanControlState, unknown, string> {
  return usePlanMutation<string>({
    mutationFn: cancelPlan,
    successMessage: 'Plan cancelled',
    errorPrefix: 'Failed to cancel plan',
  });
}

export function useResumePlan(): UseMutationResult<PlanControlState, unknown, string> {
  return usePlanMutation<string>({
    mutationFn: resumePlan,
    successMessage: 'Plan resume requested',
    errorPrefix: 'Failed to resume plan',
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
    onSuccess: async () => {
      toast.success(config.successMessage);
      await queryClient.invalidateQueries({ queryKey: PLAN_CONTROL_QUERY_KEY });
    },
    onError: (error) => {
      toast.error(`${config.errorPrefix}: ${extractErrorMessage(error)}`);
    },
  });
}

function toNoArgMutationResult(
  mutation: UseMutationResult<PlanControlState, unknown, undefined>,
): NoArgPlanMutationResult {
  return {
    ...mutation,
    mutate: () => {
      mutation.mutate(undefined);
    },
    mutateAsync: async () => mutation.mutateAsync(undefined),
  };
}

