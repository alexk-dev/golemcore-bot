import type { ApiType, LlmProviderConfig } from '../../api/settings';

export interface ApiTypeDetail {
  label: string;
  help: string;
  badgeBg: string;
  badgeText: string;
}

export const KNOWN_BASE_URLS: Record<string, string> = {
  openai: 'https://api.openai.com/v1',
  openrouter: 'https://openrouter.ai/api/v1',
  anthropic: 'https://api.anthropic.com',
  google: 'https://generativelanguage.googleapis.com/v1beta/openai',
  kimi: 'https://api.moonshot.ai/v1',
  groq: 'https://api.groq.com/openai/v1',
  together: 'https://api.together.xyz/v1',
  fireworks: 'https://api.fireworks.ai/inference/v1',
  deepseek: 'https://api.deepseek.com/v1',
  mistral: 'https://api.mistral.ai/v1',
  xai: 'https://api.x.ai/v1',
  perplexity: 'https://api.perplexity.ai',
  zhipu: 'https://open.bigmodel.cn/api/paas/v4',
  qwen: 'https://dashscope-intl.aliyuncs.com/compatible-mode/v1',
  cerebras: 'https://api.cerebras.ai/v1',
  deepinfra: 'https://api.deepinfra.com/v1/openai',
};

export const KNOWN_PROVIDERS: string[] = Object.keys(KNOWN_BASE_URLS);
export const PROVIDER_NAME_PATTERN = /^[a-z0-9][a-z0-9_-]*$/;

const KNOWN_API_TYPES: Record<string, ApiType> = {
  anthropic: 'anthropic',
  google: 'gemini',
};

export const API_TYPE_OPTIONS: ApiType[] = ['openai', 'anthropic', 'gemini'];

export const API_TYPE_DETAILS: Record<ApiType, ApiTypeDetail> = {
  openai: {
    label: 'OpenAI',
    help: 'OpenAI-compatible protocol for OpenAI, OpenRouter, Groq, DeepSeek, and similar endpoints.',
    badgeBg: 'info-subtle',
    badgeText: 'info',
  },
  anthropic: {
    label: 'Anthropic',
    help: 'Native Anthropic Claude protocol. Use for direct Anthropic providers.',
    badgeBg: 'warning-subtle',
    badgeText: 'warning',
  },
  gemini: {
    label: 'Gemini',
    help: 'Native Google Gemini protocol. Use this for direct Gemini providers.',
    badgeBg: 'success-subtle',
    badgeText: 'success',
  },
};

export function toNullableString(value: string): string | null {
  return value.length > 0 ? value : null;
}

export function toNullableInt(value: string): number | null {
  const parsed = parseInt(value, 10);
  return Number.isNaN(parsed) ? null : parsed;
}

export function normalizeApiType(value: unknown): ApiType {
  if (typeof value !== 'string') {
    return 'openai';
  }
  const normalized = value.trim().toLowerCase();
  if (API_TYPE_OPTIONS.includes(normalized as ApiType)) {
    return normalized as ApiType;
  }
  return 'openai';
}

export function getDefaultApiTypeForProvider(name: string): ApiType {
  return KNOWN_API_TYPES[name] ?? 'openai';
}

export function getSuggestedBaseUrl(name: string, apiType: ApiType): string | null {
  if (apiType === 'gemini') {
    return null;
  }

  const providerBaseUrl = KNOWN_BASE_URLS[name];
  if (providerBaseUrl != null && getDefaultApiTypeForProvider(name) === apiType) {
    return providerBaseUrl;
  }

  return apiType === 'anthropic' ? KNOWN_BASE_URLS.anthropic : KNOWN_BASE_URLS.openai;
}

export function buildDefaultProviderConfig(name: string): LlmProviderConfig {
  const defaultApiType = getDefaultApiTypeForProvider(name);
  return {
    apiKey: null,
    apiKeyPresent: false,
    baseUrl: getSuggestedBaseUrl(name, defaultApiType),
    requestTimeoutSeconds: 300,
    apiType: defaultApiType,
    legacyApi: null,
  };
}
