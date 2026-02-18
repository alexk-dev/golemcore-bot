import client from './client';

export interface UsageStats {
  totalRequests: number;
  totalTokens: number;
  totalInputTokens: number;
  totalOutputTokens: number;
  avgLatencyMs: number;
}

export interface UsageByModelEntry {
  requests: number;
  totalTokens: number;
}

export async function getUsageStats(period = '24h'): Promise<UsageStats> {
  const { data } = await client.get<UsageStats>('/usage/stats', { params: { period } });
  return data;
}

export async function getUsageByModel(period = '24h'): Promise<Record<string, UsageByModelEntry>> {
  const { data } = await client.get<Record<string, UsageByModelEntry>>('/usage/by-model', { params: { period } });
  return data;
}

export async function exportUsageMetrics(): Promise<string> {
  const { data } = await client.get<string>('/usage/export');
  return data;
}
