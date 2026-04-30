import { useQuery, type UseQueryResult } from '@tanstack/react-query';
import { getRelevantMemories, type RelevantMemoryResponse } from '../api/memory';

interface UseRelevantMemoriesOptions {
  sessionId: string;
  query?: string;
  limit?: number;
  autoRefresh?: boolean;
}

const DEFAULT_LIMIT = 10;
const REFRESH_INTERVAL_MS = 20_000;

export function useRelevantMemories(
  options: UseRelevantMemoriesOptions,
): UseQueryResult<RelevantMemoryResponse, unknown> {
  const sessionId = options.sessionId;
  const query = options.query ?? '';
  const limit = options.limit ?? DEFAULT_LIMIT;
  const autoRefresh = options.autoRefresh ?? true;
  return useQuery({
    queryKey: ['memory', 'relevant', sessionId, query, limit],
    queryFn: () => getRelevantMemories({ sessionId, query, limit }),
    enabled: sessionId.length > 0,
    staleTime: 5_000,
    refetchInterval: autoRefresh ? REFRESH_INTERVAL_MS : false,
  });
}
