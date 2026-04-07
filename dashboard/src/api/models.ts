import client from './client';

export interface ModelSettings {
  provider: string;
  displayName: string | null;
  supportsVision: boolean;
  supportsTemperature: boolean;
  maxInputTokens: number;
  reasoning: ReasoningConfig | null;
}

export interface ReasoningConfig {
  default: string;
  levels: Record<string, { maxInputTokens: number }>;
}

export interface ModelsConfig {
  models: Record<string, ModelSettings>;
  defaults: ModelSettings;
}

export interface AvailableModel {
  id: string;
  displayName: string;
  hasReasoning: boolean;
  reasoningLevels: string[];
  supportsVision: boolean;
}

export interface DiscoveredProviderModel {
  provider: string;
  id: string;
  displayName: string;
  ownedBy: string | null;
  defaultSettings: ModelSettings | null;
}

export interface ResolveModelRegistryRequest {
  provider: string;
  modelId: string;
}

export interface ResolveModelRegistryResponse {
  defaultSettings: ModelSettings | null;
  configSource: 'provider' | 'shared' | null;
  cacheStatus: 'fresh-hit' | 'stale-hit' | 'remote-hit' | 'miss';
}

export async function getModelsConfig(): Promise<ModelsConfig> {
  const { data } = await client.get<ModelsConfig>('/models');
  return data;
}

export async function replaceModelsConfig(config: ModelsConfig): Promise<ModelsConfig> {
  const { data } = await client.put<ModelsConfig>('/models', config);
  return data;
}

export async function getAvailableModels(): Promise<Record<string, AvailableModel[]>> {
  const { data } = await client.get<Record<string, AvailableModel[]>>('/models/available');
  return data;
}

export async function discoverProviderModels(provider: string): Promise<DiscoveredProviderModel[]> {
  const { data } = await client.get<DiscoveredProviderModel[]>(`/models/discover/${encodeURIComponent(provider)}`);
  return data;
}

export async function resolveModelRegistryDefaults(
  request: ResolveModelRegistryRequest,
): Promise<ResolveModelRegistryResponse> {
  const { data } = await client.post<ResolveModelRegistryResponse>('/models/registry/resolve', request);
  return data;
}

export async function saveModel(id: string, settings: ModelSettings, previousId: string | null = null): Promise<void> {
  await client.post('/models', { id, previousId, settings });
}

export async function deleteModel(id: string): Promise<void> {
  await client.delete('/models', { params: { id } });
}

export interface TestModelResponse {
  success: boolean;
  reply: string | null;
  error: string | null;
}

export async function testModel(model: string): Promise<TestModelResponse> {
  const { data } = await client.post<TestModelResponse>('/models/test', { model });
  return data;
}

export async function reloadModels(): Promise<void> {
  await client.post('/models/reload');
}
