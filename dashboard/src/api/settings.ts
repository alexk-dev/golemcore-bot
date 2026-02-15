import client from './client';

export async function getSettings() {
  const { data } = await client.get('/settings');
  return data;
}

export async function updatePreferences(prefs: Record<string, unknown>) {
  const { data } = await client.put('/settings/preferences', prefs);
  return data;
}

export async function getModels() {
  const { data } = await client.get('/settings/models');
  return data;
}

export async function updateTierOverrides(overrides: Record<string, { model: string; reasoning: string }>) {
  const { data } = await client.put('/settings/tier-overrides', overrides);
  return data;
}
