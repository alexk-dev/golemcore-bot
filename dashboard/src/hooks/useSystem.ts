import { type UseMutationResult, type UseQueryResult, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  type SystemHealthResponse,
  type SystemUpdateActionResponse,
  type SystemUpdateStatusResponse,
  checkSystemUpdate,
  getBrowserHealth,
  getSystemDiagnostics,
  getSystemHealth,
  getSystemUpdateStatus,
  updateSystemNow,
} from '../api/system';

function invalidateUpdateQueries(queryClient: ReturnType<typeof useQueryClient>): void {
  void queryClient.invalidateQueries({ queryKey: ['system', 'update', 'status'] });
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

export function useCheckSystemUpdate(): UseMutationResult<SystemUpdateActionResponse, unknown, void> {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: checkSystemUpdate,
    onSuccess: () => {
      invalidateUpdateQueries(qc);
    },
  });
}

export function useUpdateSystemNow(): UseMutationResult<SystemUpdateActionResponse, unknown, void> {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: updateSystemNow,
    onSuccess: () => {
      invalidateUpdateQueries(qc);
      void qc.invalidateQueries({ queryKey: ['system', 'health'] });
    },
  });
}

export function useBrowserHealthPing(): UseMutationResult<Awaited<ReturnType<typeof getBrowserHealth>>, unknown, void> {
  return useMutation({ mutationFn: () => getBrowserHealth() });
}
