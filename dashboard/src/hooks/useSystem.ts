import { type UseMutationResult, type UseQueryResult, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  type ConfirmTokenRequest,
  type RollbackConfirmRequest,
  type RollbackIntentRequest,
  type SystemHealthResponse,
  type SystemUpdateActionResponse,
  type SystemUpdateHistoryItem,
  type SystemUpdateIntentResponse,
  type SystemUpdateStatusResponse,
  applySystemUpdate,
  checkSystemUpdate,
  createSystemUpdateApplyIntent,
  createSystemUpdateRollbackIntent,
  getBrowserHealth,
  getSystemDiagnostics,
  getSystemHealth,
  getSystemUpdateHistory,
  getSystemUpdateStatus,
  prepareSystemUpdate,
  rollbackSystemUpdate,
} from '../api/system';

function invalidateUpdateQueries(queryClient: ReturnType<typeof useQueryClient>): void {
  void queryClient.invalidateQueries({ queryKey: ['system', 'update', 'status'] });
  void queryClient.invalidateQueries({ queryKey: ['system', 'update', 'history'] });
}

export function useSystemHealth(): UseQueryResult<SystemHealthResponse, unknown> {
  return useQuery({ queryKey: ['system', 'health'], queryFn: getSystemHealth, refetchInterval: 30000 });
}

export function useSystemDiagnostics(): UseQueryResult<Awaited<ReturnType<typeof getSystemDiagnostics>>, unknown> {
  return useQuery({ queryKey: ['system', 'diagnostics'], queryFn: getSystemDiagnostics, refetchInterval: 10000 });
}

export function useSystemUpdateStatus(): UseQueryResult<SystemUpdateStatusResponse, unknown> {
  return useQuery({
    queryKey: ['system', 'update', 'status'],
    queryFn: getSystemUpdateStatus,
    refetchInterval: 30000,
    retry: false,
  });
}

export function useSystemUpdateHistory(): UseQueryResult<SystemUpdateHistoryItem[], unknown> {
  return useQuery({
    queryKey: ['system', 'update', 'history'],
    queryFn: getSystemUpdateHistory,
    retry: false,
  });
}

export function useCheckSystemUpdate(): UseMutationResult<SystemUpdateActionResponse, unknown, void> {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: checkSystemUpdate,
    onSuccess: () => {
      invalidateUpdateQueries(qc);
    },
  });
}

export function usePrepareSystemUpdate(): UseMutationResult<SystemUpdateActionResponse, unknown, void> {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: prepareSystemUpdate,
    onSuccess: () => {
      invalidateUpdateQueries(qc);
    },
  });
}

export function useCreateSystemUpdateApplyIntent(): UseMutationResult<SystemUpdateIntentResponse, unknown, void> {
  return useMutation({ mutationFn: createSystemUpdateApplyIntent });
}

export function useApplySystemUpdate(): UseMutationResult<SystemUpdateActionResponse, unknown, ConfirmTokenRequest> {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (payload: ConfirmTokenRequest) => applySystemUpdate(payload),
    onSuccess: () => {
      invalidateUpdateQueries(qc);
      void qc.invalidateQueries({ queryKey: ['system', 'health'] });
    },
  });
}

export function useCreateSystemUpdateRollbackIntent(): UseMutationResult<SystemUpdateIntentResponse, unknown, RollbackIntentRequest | undefined> {
  return useMutation({ mutationFn: (payload?: RollbackIntentRequest) => createSystemUpdateRollbackIntent(payload) });
}

export function useRollbackSystemUpdate(): UseMutationResult<SystemUpdateActionResponse, unknown, RollbackConfirmRequest> {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (payload: RollbackConfirmRequest) => rollbackSystemUpdate(payload),
    onSuccess: () => {
      invalidateUpdateQueries(qc);
      void qc.invalidateQueries({ queryKey: ['system', 'health'] });
    },
  });
}

export function useBrowserHealthPing(): UseMutationResult<Awaited<ReturnType<typeof getBrowserHealth>>, unknown, void> {
  return useMutation({ mutationFn: () => getBrowserHealth() });
}
