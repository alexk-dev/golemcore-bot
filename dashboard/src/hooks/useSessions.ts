import { type UseMutationResult, type UseQueryResult, useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import {
  listSessions,
  listRecentSessions,
  getActiveSession,
  setActiveSession,
  createSession,
  getSession,
  deleteSession,
  compactSession,
  clearSession,
} from '../api/sessions';

export function useSessions(channel?: string): UseQueryResult<Awaited<ReturnType<typeof listSessions>>, unknown> {
  return useQuery({
    queryKey: ['sessions', channel],
    queryFn: () => listSessions(channel),
  });
}

export function useRecentSessions(
  channel: string,
  clientInstanceId: string,
  limit = 5,
): UseQueryResult<Awaited<ReturnType<typeof listRecentSessions>>, unknown> {
  return useQuery({
    queryKey: ['sessions', 'recent', channel, clientInstanceId, limit],
    queryFn: () => listRecentSessions(channel, clientInstanceId, limit),
    enabled: clientInstanceId.length > 0,
  });
}

export function useActiveSession(
  channel: string,
  clientInstanceId: string,
): UseQueryResult<Awaited<ReturnType<typeof getActiveSession>>, unknown> {
  return useQuery({
    queryKey: ['sessions', 'active', channel, clientInstanceId],
    queryFn: () => getActiveSession(channel, clientInstanceId),
    enabled: clientInstanceId.length > 0,
  });
}

export function useSession(id: string): UseQueryResult<Awaited<ReturnType<typeof getSession>>, unknown> {
  return useQuery({
    queryKey: ['sessions', id],
    queryFn: () => getSession(id),
    enabled: id.length > 0,
  });
}

export function useSetActiveSession(): UseMutationResult<Awaited<ReturnType<typeof setActiveSession>>, unknown, Parameters<typeof setActiveSession>[0]> {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: setActiveSession,
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ['sessions', 'active'] });
      void qc.invalidateQueries({ queryKey: ['sessions', 'recent'] });
    },
  });
}

export function useCreateSession(): UseMutationResult<Awaited<ReturnType<typeof createSession>>, unknown, Parameters<typeof createSession>[0]> {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: createSession,
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ['sessions'] });
      void qc.invalidateQueries({ queryKey: ['sessions', 'recent'] });
      void qc.invalidateQueries({ queryKey: ['sessions', 'active'] });
    },
  });
}

export function useDeleteSession(): UseMutationResult<Awaited<ReturnType<typeof deleteSession>>, unknown, string> {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: deleteSession,
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ['sessions'] });
      void qc.invalidateQueries({ queryKey: ['sessions', 'recent'] });
      void qc.invalidateQueries({ queryKey: ['sessions', 'active'] });
    },
  });
}

export function useCompactSession(): UseMutationResult<Awaited<ReturnType<typeof compactSession>>, unknown, { id: string; keepLast?: number }> {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, keepLast }: { id: string; keepLast?: number }) => compactSession(id, keepLast),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ['sessions'] });
      void qc.invalidateQueries({ queryKey: ['sessions', 'recent'] });
    },
  });
}

export function useClearSession(): UseMutationResult<Awaited<ReturnType<typeof clearSession>>, unknown, string> {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: clearSession,
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ['sessions'] });
      void qc.invalidateQueries({ queryKey: ['sessions', 'recent'] });
    },
  });
}
