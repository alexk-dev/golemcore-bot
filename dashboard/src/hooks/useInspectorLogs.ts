import { useQuery, type UseQueryResult } from '@tanstack/react-query';
import { getSystemLogs, type LogEntryResponse } from '../api/system';

const INSPECTOR_LOGS_LIMIT = 30;
const INSPECTOR_LOGS_REFRESH_MS = 15_000;

interface InspectorLogsResult {
  entries: LogEntryResponse[];
}

async function fetchInspectorLogs(): Promise<InspectorLogsResult> {
  const page = await getSystemLogs({ limit: INSPECTOR_LOGS_LIMIT });
  return { entries: page.items };
}

export function useInspectorLogs(): UseQueryResult<InspectorLogsResult, unknown> {
  return useQuery({
    queryKey: ['system', 'logs', 'inspector'],
    queryFn: fetchInspectorLogs,
    refetchInterval: INSPECTOR_LOGS_REFRESH_MS,
    staleTime: 5_000,
  });
}
