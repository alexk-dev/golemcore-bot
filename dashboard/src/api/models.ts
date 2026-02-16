import client from './client';

export interface ModelSettings {
  provider: string;
  displayName: string | null;
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
}

export async function getModelsConfig(): Promise<ModelsConfig> {
  const { data } = await client.get('/models');
  return data;
}

export async function replaceModelsConfig(config: ModelsConfig): Promise<ModelsConfig> {
  const { data } = await client.put('/models', config);
  return data;
}

export async function getAvailableModels(): Promise<Record<string, AvailableModel[]>> {
  const { data } = await client.get('/models/available');
  return data;
}

export async function saveModel(id: string, settings: ModelSettings): Promise<void> {
  await client.post(`/models/${id}`, settings);
}

export async function deleteModel(id: string): Promise<void> {
  await client.delete(`/models/${id}`);
}

export async function reloadModels(): Promise<void> {
  await client.post('/models/reload');
}
