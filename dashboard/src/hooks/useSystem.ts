import { type UseMutationResult, type UseQueryResult, useMutation, useQuery } from '@tanstack/react-query';
import { getBrowserHealth, getSystemDiagnostics } from '../api/system';

export function useSystemDiagnostics(): UseQueryResult<Awaited<ReturnType<typeof getSystemDiagnostics>>, unknown> {
  return useQuery({ queryKey: ['system', 'diagnostics'], queryFn: getSystemDiagnostics, refetchInterval: 10000 });
}

export function useBrowserHealthPing(): UseMutationResult<Awaited<ReturnType<typeof getBrowserHealth>>, unknown, void> {
  return useMutation({ mutationFn: () => getBrowserHealth() });
}
