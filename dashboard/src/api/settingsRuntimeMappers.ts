import { toModelRegistryConfig, toModelRouterConfig } from './settingsModelMappers';
import { toSelfEvolvingConfig } from './settingsSelfEvolvingMappers';
import type { GonkaEndpointConfig, RuntimeConfig } from './settingsTypes';
import { hasSecretValue, type UnknownRecord } from './settingsUtils';
import { normalizeLlmApiType, toSecretPayload } from './settingsApiUtils';

function scrubSecret(): null {
  return null;
}

function toShellEnvironmentVariables(value: unknown): RuntimeConfig['tools']['shellEnvironmentVariables'] {
  if (!Array.isArray(value)) {
    return [];
  }
  return value.flatMap((entry) => {
    if (entry == null || typeof entry !== 'object') {
      return [];
    }
    const record = entry as UnknownRecord;
    const name = typeof record.name === 'string' ? record.name.trim() : '';
    return name.length === 0 ? [] : [{ name, value: typeof record.value === 'string' ? record.value : '' }];
  });
}

export interface RuntimeConfigUiRecord extends UnknownRecord {
  telegram?: UnknownRecord;
  modelRouter?: unknown;
  llm?: { providers?: Record<string, UnknownRecord> } & UnknownRecord;
  tools?: UnknownRecord;
  voice?: UnknownRecord;
  hive?: UnknownRecord;
  selfEvolving?: unknown;
  modelRegistry?: unknown;
}

export function toUiRuntimeConfig(data: RuntimeConfigUiRecord): RuntimeConfig {
  const cfg: RuntimeConfigUiRecord = {
    ...data,
    plan: typeof data.plan === 'object' && data.plan != null ? { ...data.plan } : {},
    hive: typeof data.hive === 'object' && data.hive != null ? { ...data.hive } : {},
  };
  if (cfg.telegram) {
    cfg.telegram = { ...cfg.telegram, token: scrubSecret(), tokenPresent: hasSecretValue(cfg.telegram.token) };
  }
  if (cfg.llm?.providers != null) {
    cfg.llm = { ...cfg.llm, providers: normalizeLlmProviders(cfg.llm.providers) };
  }
  if (cfg.tools) {
    cfg.tools = { ...cfg.tools, shellEnvironmentVariables: toShellEnvironmentVariables(cfg.tools.shellEnvironmentVariables) };
  }
  if (cfg.voice) {
    cfg.voice = {
      ...cfg.voice,
      apiKey: scrubSecret(),
      apiKeyPresent: hasSecretValue(cfg.voice.apiKey),
      whisperSttApiKey: scrubSecret(),
      whisperSttApiKeyPresent: hasSecretValue(cfg.voice.whisperSttApiKey),
    };
  }
  cfg.modelRouter = toModelRouterConfig(cfg.modelRouter);
  cfg.selfEvolving = toSelfEvolvingConfig(cfg.selfEvolving);
  cfg.modelRegistry = toModelRegistryConfig(cfg.modelRegistry);
  return cfg as unknown as RuntimeConfig;
}

function normalizeLlmProviders(providers: Record<string, UnknownRecord>): Record<string, UnknownRecord> {
  return Object.fromEntries(Object.entries(providers).map(([name, provider]) => [name, {
    ...provider,
    apiKey: scrubSecret(),
    apiKeyPresent: hasSecretValue(provider.apiKey as UnknownRecord | undefined),
    apiType: normalizeLlmApiType(provider.apiType),
    legacyApi: provider.legacyApi === true ? true : null,
    sourceUrl: typeof provider.sourceUrl === 'string' ? provider.sourceUrl : null,
    gonkaAddress: typeof provider.gonkaAddress === 'string' ? provider.gonkaAddress : null,
    endpoints: toGonkaEndpoints(provider.endpoints),
  }]));
}

export function toBackendRuntimeConfig(config: RuntimeConfig): UnknownRecord {
  const { tokenPresent: _telegramTokenPresent, ...telegram } = config.telegram;
  const { apiKeyPresent: _voiceApiKeyPresent, whisperSttApiKeyPresent: _whisperSttApiKeyPresent, ...voice } = config.voice;
  const normalizedSelfEvolving = toSelfEvolvingConfig(config.selfEvolving);
  const { managedByProperties: _managedByProperties, overriddenPaths: _overriddenPaths, ...selfEvolving } = normalizedSelfEvolving;
  return {
    ...config,
    telegram: { ...telegram, token: toSecretPayload(telegram.token ?? null) },
    llm: { ...config.llm, providers: toBackendLlmProviders(config.llm.providers) },
    tools: { ...config.tools },
    voice: { ...voice, apiKey: toSecretPayload(voice.apiKey ?? null), whisperSttApiKey: toSecretPayload(voice.whisperSttApiKey ?? null) },
    selfEvolving: toBackendSelfEvolvingConfig(selfEvolving),
  };
}

function toBackendLlmProviders(providers: RuntimeConfig['llm']['providers']): UnknownRecord {
  return Object.fromEntries(Object.entries(providers).map(([name, provider]) => [name, {
    baseUrl: provider.baseUrl,
    requestTimeoutSeconds: provider.requestTimeoutSeconds,
    apiKey: toSecretPayload(provider.apiKey ?? null),
    apiType: normalizeLlmApiType(provider.apiType),
    legacyApi: provider.legacyApi === true ? true : null,
    sourceUrl: provider.sourceUrl,
    gonkaAddress: provider.gonkaAddress,
    endpoints: provider.endpoints,
  }]));
}

function toGonkaEndpoints(value: unknown): GonkaEndpointConfig[] {
  if (!Array.isArray(value)) {
    return [];
  }
  return value.flatMap((entry) => {
    if (entry == null || typeof entry !== 'object') {
      return [];
    }
    const record = entry as UnknownRecord;
    const url = typeof record.url === 'string' ? record.url.trim() : '';
    const transferAddress = typeof record.transferAddress === 'string' ? record.transferAddress.trim() : '';
    return url.length > 0 && transferAddress.length > 0 ? [{ url, transferAddress }] : [];
  });
}

function toBackendSelfEvolvingConfig(selfEvolving: UnknownRecord): UnknownRecord {
  const tactics = selfEvolving.tactics as UnknownRecord | undefined;
  const search = tactics?.search as UnknownRecord | undefined;
  const embeddings = search?.embeddings as UnknownRecord | undefined;
  if (tactics == null || search == null || embeddings == null) {
    return selfEvolving;
  }
  const { apiKey, apiKeyPresent: _apiKeyPresent, ...embeddingsRest } = embeddings;
  return { ...selfEvolving, tactics: { ...tactics, search: { ...search, embeddings: { ...embeddingsRest, apiKey: toSecretPayload(typeof apiKey === 'string' ? apiKey : null) } } } };
}
