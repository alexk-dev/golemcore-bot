import { type UseQueryResult, useQuery } from '@tanstack/react-query';
import { getUsageStats, getUsageByModel } from '../api/usage';

export function useUsageStats(period = '24h'): UseQueryResult<Awaited<ReturnType<typeof getUsageStats>>, unknown> {
  return useQuery({
    queryKey: ['usage', 'stats', period],
    queryFn: () => getUsageStats(period),
  });
}

export function useUsageByModel(period = '24h'): UseQueryResult<Awaited<ReturnType<typeof getUsageByModel>>, unknown> {
  return useQuery({
    queryKey: ['usage', 'byModel', period],
    queryFn: () => getUsageByModel(period),
  });
}
