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

export interface UsageMetric {
  name: string;
  tags: Record<string, string>;
  value: number;
  timestamp: string;
}

export async function getUsageStats(period = '24h'): Promise<UsageStats> {
  const { data } = await client.get<UsageStats>('/usage/stats', { params: { period } });
  return data;
}

export async function getUsageByModel(period = '24h'): Promise<Record<string, UsageByModelEntry>> {
  const { data } = await client.get<Record<string, UsageByModelEntry>>('/usage/by-model', { params: { period } });
  return data;
}

export async function exportUsageMetrics(): Promise<UsageMetric[]> {
  const { data } = await client.get<UsageMetric[]>('/usage/export');
  return data;
}
