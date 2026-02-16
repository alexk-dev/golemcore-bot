import { useQuery } from '@tanstack/react-query';
import { getSystemDiagnostics } from '../api/system';

export function useSystemDiagnostics() {
  return useQuery({ queryKey: ['system', 'diagnostics'], queryFn: getSystemDiagnostics, refetchInterval: 10000 });
}
