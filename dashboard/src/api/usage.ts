import client from './client';

export async function getUsageStats(period = '24h') {
  const { data } = await client.get('/usage/stats', { params: { period } });
  return data;
}

export async function getUsageByModel(period = '24h') {
  const { data } = await client.get('/usage/by-model', { params: { period } });
  return data;
}

export async function exportUsageMetrics() {
  const { data } = await client.get('/usage/export');
  return data;
}
