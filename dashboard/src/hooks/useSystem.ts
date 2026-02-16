import { useMutation, useQuery } from '@tanstack/react-query';
import { getBrowserHealth, getSystemDiagnostics } from '../api/system';

export function useSystemDiagnostics() {
  return useQuery({ queryKey: ['system', 'diagnostics'], queryFn: getSystemDiagnostics, refetchInterval: 10000 });
}

export function useBrowserHealthPing() {
  return useMutation({ mutationFn: () => getBrowserHealth() });
}
