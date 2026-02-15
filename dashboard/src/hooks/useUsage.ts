import { useQuery } from '@tanstack/react-query';
import { getUsageStats, getUsageByModel } from '../api/usage';

export function useUsageStats(period = '24h') {
  return useQuery({
    queryKey: ['usage', 'stats', period],
    queryFn: () => getUsageStats(period),
  });
}

export function useUsageByModel(period = '24h') {
  return useQuery({
    queryKey: ['usage', 'byModel', period],
    queryFn: () => getUsageByModel(period),
  });
}
