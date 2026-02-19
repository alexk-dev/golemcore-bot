import { type UseMutationResult, type UseQueryResult, useMutation, useQuery } from '@tanstack/react-query';
import { type SystemHealthResponse, getBrowserHealth, getSystemDiagnostics, getSystemHealth } from '../api/system';

export function useSystemHealth(): UseQueryResult<SystemHealthResponse, unknown> {
  return useQuery({ queryKey: ['system', 'health'], queryFn: getSystemHealth, refetchInterval: 30000 });
}

export function useSystemDiagnostics(): UseQueryResult<Awaited<ReturnType<typeof getSystemDiagnostics>>, unknown> {
  return useQuery({ queryKey: ['system', 'diagnostics'], queryFn: getSystemDiagnostics, refetchInterval: 10000 });
}

export function useBrowserHealthPing(): UseMutationResult<Awaited<ReturnType<typeof getBrowserHealth>>, unknown, void> {
  return useMutation({ mutationFn: () => getBrowserHealth() });
}
