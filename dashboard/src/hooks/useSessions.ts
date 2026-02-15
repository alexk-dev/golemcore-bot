import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { listSessions, getSession, deleteSession, compactSession, clearSession } from '../api/sessions';

export function useSessions(channel?: string) {
  return useQuery({
    queryKey: ['sessions', channel],
    queryFn: () => listSessions(channel),
  });
}

export function useSession(id: string) {
  return useQuery({
    queryKey: ['sessions', id],
    queryFn: () => getSession(id),
    enabled: !!id,
  });
}

export function useDeleteSession() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: deleteSession,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['sessions'] }),
  });
}

export function useCompactSession() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, keepLast }: { id: string; keepLast?: number }) => compactSession(id, keepLast),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['sessions'] }),
  });
}

export function useClearSession() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: clearSession,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['sessions'] }),
  });
}
