import type { ApiType, LlmConfig } from '../../../api/settingsTypes';

export interface ProviderProfileSummary {
  name: string;
  apiType: ApiType | null;
  isReady: boolean;
}

export function getProviderProfileSummaries(llmConfig: LlmConfig): ProviderProfileSummary[] {
  return Object.entries(llmConfig.providers ?? {})
    .map(([name, config]) => ({
      name,
      apiType: config.apiType ?? null,
      isReady: config.apiKeyPresent === true,
    }))
    .sort((left, right) => left.name.localeCompare(right.name));
}
