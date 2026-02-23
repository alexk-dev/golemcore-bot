import type { ModelRouterConfig, RuntimeConfig } from '../api/settings';

const STARTUP_SETUP_DISMISSED_COOKIE = 'golemcore_startup_setup_dismissed';

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

function getConfiguredTierModels(modelRouter: ModelRouterConfig): string[] {
  return [
    modelRouter.routingModel,
    modelRouter.balancedModel,
    modelRouter.smartModel,
    modelRouter.codingModel,
    modelRouter.deepModel,
  ].filter(hasText);
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

  const tierModels = getConfiguredTierModels(config.modelRouter);
  if (tierModels.length === 0) {
    return false;
  }
  return tierModels.every((modelSpec) => {
    const providerName = extractProviderName(modelSpec);
    if (providerName == null) {
      return true;
    }
    return readyProviders.has(providerName);
  });
}

export function isStartupSetupComplete(config: RuntimeConfig): boolean {
  return hasConfiguredLlmProvider(config) && hasCompatibleModelRouting(config);
}

export function isStartupSetupInviteDismissed(): boolean {
  if (typeof document === 'undefined') {
    return false;
  }
  const cookieName = `${STARTUP_SETUP_DISMISSED_COOKIE}=`;
  return document.cookie.split(';').some((rawCookie) => rawCookie.trim().startsWith(cookieName));
}

export function dismissStartupSetupInviteForSession(): void {
  if (typeof document === 'undefined') {
    return;
  }
  document.cookie = `${STARTUP_SETUP_DISMISSED_COOKIE}=1; path=/; SameSite=Lax`;
}
