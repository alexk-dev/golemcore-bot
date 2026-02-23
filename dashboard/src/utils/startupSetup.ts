import type { ModelRouterConfig, RuntimeConfig } from '../api/settings';

const DEFAULT_ROUTING_MODEL = 'openai/gpt-5.2-codex';
const DEFAULT_BALANCED_MODEL = 'openai/gpt-5.1';
const DEFAULT_SMART_MODEL = 'openai/gpt-5.1';
const DEFAULT_CODING_MODEL = 'openai/gpt-5.2';
const DEFAULT_DEEP_MODEL = 'openai/gpt-5.2';

function hasText(value: string | null | undefined): value is string {
  return value != null && value.trim().length > 0;
}

function extractProviderName(modelSpec: string): string | null {
  const separatorIndex = modelSpec.indexOf('/');
  if (separatorIndex <= 0) {
    return null;
  }
  return modelSpec.slice(0, separatorIndex);
}

function resolveTierModel(
  model: string | null,
  fallback: string,
): string {
  if (hasText(model)) {
    return model;
  }
  return fallback;
}

function getRequiredTierModels(modelRouter: ModelRouterConfig): string[] {
  return [
    resolveTierModel(modelRouter.routingModel, DEFAULT_ROUTING_MODEL),
    resolveTierModel(modelRouter.balancedModel, DEFAULT_BALANCED_MODEL),
    resolveTierModel(modelRouter.smartModel, DEFAULT_SMART_MODEL),
    resolveTierModel(modelRouter.codingModel, DEFAULT_CODING_MODEL),
    resolveTierModel(modelRouter.deepModel, DEFAULT_DEEP_MODEL),
  ];
}

export function getReadyLlmProviders(config: RuntimeConfig): string[] {
  return Object.entries(config.llm.providers ?? {})
    .filter(([, provider]) => provider.apiKeyPresent === true)
    .map(([name]) => name);
}

export function hasConfiguredLlmProvider(config: RuntimeConfig): boolean {
  return getReadyLlmProviders(config).length > 0;
}

export function hasCompatibleModelRouting(config: RuntimeConfig): boolean {
  const readyProviders = new Set(getReadyLlmProviders(config));
  if (readyProviders.size === 0) {
    return false;
  }

  const tierModels = getRequiredTierModels(config.modelRouter);
  return tierModels.every((modelSpec) => {
    const providerName = extractProviderName(modelSpec);
    return providerName != null && readyProviders.has(providerName);
  });
}

export function isStartupSetupComplete(config: RuntimeConfig): boolean {
  return hasConfiguredLlmProvider(config) && hasCompatibleModelRouting(config);
}
