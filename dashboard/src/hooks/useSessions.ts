import { type UseMutationResult, type UseQueryResult, useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { listSessions, getSession, deleteSession, compactSession, clearSession } from '../api/sessions';

export function useSessions(channel?: string): UseQueryResult<Awaited<ReturnType<typeof listSessions>>, unknown> {
  return useQuery({
    queryKey: ['sessions', channel],
    queryFn: () => listSessions(channel),
  });
}

export function useSession(id: string): UseQueryResult<Awaited<ReturnType<typeof getSession>>, unknown> {
  return useQuery({
    queryKey: ['sessions', id],
    queryFn: () => getSession(id),
    enabled: id.length > 0,
  });
}

export function useDeleteSession(): UseMutationResult<Awaited<ReturnType<typeof deleteSession>>, unknown, string> {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: deleteSession,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['sessions'] }),
  });
}

export function useCompactSession(): UseMutationResult<Awaited<ReturnType<typeof compactSession>>, unknown, { id: string; keepLast?: number }> {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, keepLast }: { id: string; keepLast?: number }) => compactSession(id, keepLast),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['sessions'] }),
  });
}

export function useClearSession(): UseMutationResult<Awaited<ReturnType<typeof clearSession>>, unknown, string> {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: clearSession,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['sessions'] }),
  });
}
