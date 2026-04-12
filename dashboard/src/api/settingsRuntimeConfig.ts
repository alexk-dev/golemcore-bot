import client from './client';
import { toBackendRuntimeConfig, toUiRuntimeConfig, type RuntimeConfigUiRecord } from './settingsRuntimeMappers';
import type { RuntimeConfig } from './settingsTypes';

export async function getRuntimeConfig(): Promise<RuntimeConfig> {
  const { data } = await client.get<RuntimeConfigUiRecord>('/settings/runtime');
  return toUiRuntimeConfig(data);
}

export async function updateRuntimeConfig(config: RuntimeConfig): Promise<RuntimeConfig> {
  const { data } = await client.put<RuntimeConfigUiRecord>('/settings/runtime', toBackendRuntimeConfig(config));
  return toUiRuntimeConfig(data);
}
