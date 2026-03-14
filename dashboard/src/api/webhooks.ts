import client from './client';

export type HookAction = 'wake' | 'agent';
export type HookAuthMode = 'bearer' | 'hmac';

export interface HookMapping {
  name: string;
  action: HookAction;
  authMode: HookAuthMode;
  hmacHeader: string | null;
  hmacSecret: string | null;
  hmacSecretPresent?: boolean;
  hmacPrefix: string | null;
  messageTemplate: string | null;
  model: string | null;
  deliver: boolean;
  channel: string | null;
  to: string | null;
}

export type HookMappingDraft = HookMapping;

export interface WebhookConfig {
  enabled: boolean;
  token: string | null;
  tokenPresent?: boolean;
  maxPayloadSize: number;
  defaultTimeoutSeconds: number;
  mappings: HookMapping[];
}

const DEFAULT_MAX_PAYLOAD_SIZE = 65536;
const DEFAULT_TIMEOUT_SECONDS = 300;
const HOOK_NAME_PATTERN = /^[a-z0-9][a-z0-9-]*$/;

type UnknownRecord = Record<string, unknown>;

interface SecretPayload {
  value: string | null;
  encrypted: boolean;
}

function toSecretPayload(value: string | null | undefined): SecretPayload | null {
  if (value == null || value.trim().length === 0) {
    return null;
  }
  return { value, encrypted: false };
}

function hasSecretValue(secret: unknown): boolean {
  if (secret == null || typeof secret !== 'object') {
    return false;
  }
  const record = secret as UnknownRecord;
  const value = record.value;
  const present = record.present;
  return Boolean(present) || (typeof value === 'string' && value.length > 0);
}

function toNullableString(value: unknown): string | null {
  if (typeof value !== 'string') {
    return null;
  }
  const normalized = value.trim();
  return normalized.length > 0 ? normalized : null;
}

function toNullableTemplate(value: unknown): string | null {
  if (typeof value !== 'string') {
    return null;
  }
  return value.length > 0 ? value : null;
}

function toNullableSecretValue(value: unknown): string | null {
  if (typeof value === 'string') {
    return toNullableString(value);
  }
  if (value == null || typeof value !== 'object') {
    return null;
  }
  const record = value as UnknownRecord;
  return toNullableString(record.value);
}

function toNumberOrDefault(value: unknown, fallback: number): number {
  if (typeof value === 'number' && Number.isFinite(value) && value > 0) {
    return Math.floor(value);
  }
  return fallback;
}

function toHookAction(value: unknown): HookAction {
  return value === 'agent' ? 'agent' : 'wake';
}

function toHookAuthMode(value: unknown): HookAuthMode {
  return value === 'hmac' ? 'hmac' : 'bearer';
}

function normalizeMapping(raw: unknown): HookMapping {
  const record = (raw != null && typeof raw === 'object') ? (raw as UnknownRecord) : {};
  const hmacSecretRaw = record.hmacSecret;

  return {
    name: typeof record.name === 'string' ? record.name : '',
    action: toHookAction(record.action),
    authMode: toHookAuthMode(record.authMode),
    hmacHeader: toNullableString(record.hmacHeader),
    hmacSecret: toNullableSecretValue(hmacSecretRaw),
    hmacSecretPresent: hasSecretValue(hmacSecretRaw),
    hmacPrefix: toNullableString(record.hmacPrefix),
    messageTemplate: toNullableTemplate(record.messageTemplate),
    model: toNullableString(record.model),
    deliver: Boolean(record.deliver),
    channel: toNullableString(record.channel),
    to: toNullableString(record.to),
  };
}

function toBackendMapping(mapping: HookMapping): UnknownRecord {
  return {
    name: mapping.name.trim(),
    action: mapping.action,
    authMode: mapping.authMode,
    hmacHeader: toNullableString(mapping.hmacHeader),
    hmacSecret: toSecretPayload(mapping.hmacSecret),
    hmacPrefix: toNullableString(mapping.hmacPrefix),
    messageTemplate: toNullableTemplate(mapping.messageTemplate),
    model: toNullableString(mapping.model),
    deliver: mapping.deliver,
    channel: toNullableString(mapping.channel),
    to: toNullableString(mapping.to),
  };
}

function parseWebhookConfig(raw: unknown): WebhookConfig {
  const record = (raw != null && typeof raw === 'object') ? (raw as UnknownRecord) : {};
  const mappingsRaw = Array.isArray(record.mappings) ? record.mappings : [];
  const tokenRaw = record.token;

  return {
    enabled: Boolean(record.enabled),
    token: toNullableSecretValue(tokenRaw),
    tokenPresent: hasSecretValue(tokenRaw),
    maxPayloadSize: toNumberOrDefault(record.maxPayloadSize, DEFAULT_MAX_PAYLOAD_SIZE),
    defaultTimeoutSeconds: toNumberOrDefault(record.defaultTimeoutSeconds, DEFAULT_TIMEOUT_SECONDS),
    mappings: mappingsRaw.map((item) => normalizeMapping(item)),
  };
}

function toBackendWebhookConfig(config: WebhookConfig): UnknownRecord {
  return {
    enabled: config.enabled,
    token: toSecretPayload(config.token),
    maxPayloadSize: config.maxPayloadSize,
    defaultTimeoutSeconds: config.defaultTimeoutSeconds,
    mappings: config.mappings.map((mapping) => toBackendMapping(mapping)),
  };
}

export function createDefaultWebhookConfig(): WebhookConfig {
  return {
    enabled: false,
    token: null,
    tokenPresent: false,
    maxPayloadSize: DEFAULT_MAX_PAYLOAD_SIZE,
    defaultTimeoutSeconds: DEFAULT_TIMEOUT_SECONDS,
    mappings: [],
  };
}

export function createEmptyWebhookMapping(): HookMappingDraft {
  return {
    name: '',
    action: 'wake',
    authMode: 'bearer',
    hmacHeader: null,
    hmacSecret: null,
    hmacSecretPresent: false,
    hmacPrefix: null,
    messageTemplate: null,
    model: null,
    deliver: false,
    channel: null,
    to: null,
  };
}

export interface WebhookValidationResult {
  valid: boolean;
  issues: string[];
}

function validateGlobalSettings(config: WebhookConfig, issues: string[]): void {
  const tokenMissing = (config.token == null || config.token.trim().length === 0) && config.tokenPresent !== true;

  if (config.enabled && tokenMissing) {
    issues.push('Webhook token is required when webhooks are enabled.');
  }

  if (config.maxPayloadSize < 1024) {
    issues.push('Max payload size should be at least 1024 bytes.');
  }

  if (config.defaultTimeoutSeconds < 5) {
    issues.push('Default timeout should be at least 5 seconds.');
  }
}

function validateMappingName(mapping: HookMapping, index: number, seenNames: Set<string>, issues: string[]): void {
  const prefix = `Mapping #${index + 1}`;
  const normalizedName = mapping.name.trim();

  if (normalizedName.length === 0) {
    issues.push(`${prefix}: name is required.`);
    return;
  }

  if (!HOOK_NAME_PATTERN.test(normalizedName)) {
    issues.push(`${prefix}: name must match [a-z0-9][a-z0-9-]*.`);
    return;
  }

  if (seenNames.has(normalizedName)) {
    issues.push(`${prefix}: duplicate name '${normalizedName}'.`);
    return;
  }

  seenNames.add(normalizedName);
}

function validateMappingAuth(mapping: HookMapping, index: number, issues: string[]): void {
  if (mapping.authMode !== 'hmac') {
    return;
  }

  const prefix = `Mapping #${index + 1}`;
  const missingSecret = (mapping.hmacSecret == null || mapping.hmacSecret.trim().length === 0)
    && mapping.hmacSecretPresent !== true;

  if (mapping.hmacHeader == null || mapping.hmacHeader.trim().length === 0) {
    issues.push(`${prefix}: HMAC header is required for authMode=hmac.`);
  }

  if (missingSecret) {
    issues.push(`${prefix}: HMAC secret is required for authMode=hmac.`);
  }
}

function validateMappingDelivery(mapping: HookMapping, index: number, issues: string[]): void {
  if (mapping.action !== 'agent' || !mapping.deliver) {
    return;
  }

  const prefix = `Mapping #${index + 1}`;

  if (mapping.channel == null || mapping.channel.trim().length === 0) {
    issues.push(`${prefix}: channel is required when delivery is enabled.`);
  }

  if (mapping.to == null || mapping.to.trim().length === 0) {
    issues.push(`${prefix}: delivery target (to) is required when delivery is enabled.`);
  }
}

export function validateWebhookConfig(config: WebhookConfig): WebhookValidationResult {
  const issues: string[] = [];
  const seenNames = new Set<string>();

  validateGlobalSettings(config, issues);

  config.mappings.forEach((mapping, index) => {
    validateMappingName(mapping, index, seenNames, issues);
    validateMappingAuth(mapping, index, issues);
    validateMappingDelivery(mapping, index, issues);
  });

  return {
    valid: issues.length === 0,
    issues,
  };
}

export async function getWebhookConfig(): Promise<WebhookConfig> {
  const { data } = await client.get<UnknownRecord>('/settings');
  return parseWebhookConfig(data.webhooks);
}

export async function updateWebhookConfig(config: WebhookConfig): Promise<void> {
  await client.put('/settings/runtime/webhooks', toBackendWebhookConfig(config));
}
