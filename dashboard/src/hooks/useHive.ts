import { type UseMutationResult, type UseQueryResult, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { type HiveStatusResponse, getHiveStatus, joinHive, reconnectHive, leaveHive } from '../api/hive';

export function useHiveStatus(): UseQueryResult<HiveStatusResponse, unknown> {
  return useQuery({
    queryKey: ['hive-status'],
    queryFn: getHiveStatus,
    refetchInterval: 10000,
  });
}

export function useJoinHive(): UseMutationResult<HiveStatusResponse, unknown, { joinCode: string | null }> {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ joinCode }: { joinCode: string | null }) => joinHive({ joinCode }),
    onSuccess: () => Promise.all([
      qc.invalidateQueries({ queryKey: ['hive-status'] }),
      qc.invalidateQueries({ queryKey: ['runtime-config'] }),
    ]),
  });
}

export function useReconnectHive(): UseMutationResult<HiveStatusResponse, unknown, void> {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: () => reconnectHive(),
    onSuccess: () => Promise.all([
      qc.invalidateQueries({ queryKey: ['hive-status'] }),
      qc.invalidateQueries({ queryKey: ['runtime-config'] }),
    ]),
  });
}

export function useLeaveHive(): UseMutationResult<HiveStatusResponse, unknown, void> {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: () => leaveHive(),
    onSuccess: () => Promise.all([
      qc.invalidateQueries({ queryKey: ['hive-status'] }),
      qc.invalidateQueries({ queryKey: ['runtime-config'] }),
    ]),
  });
}
