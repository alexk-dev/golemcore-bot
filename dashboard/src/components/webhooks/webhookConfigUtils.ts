import type { HookMapping, WebhookConfig } from '../../api/webhooks';
import type { WebhookSummary } from './WebhooksPageHeader';

export function hasWebhookConfigChanges(current: WebhookConfig, initial: WebhookConfig): boolean {
  return JSON.stringify(current) !== JSON.stringify(initial);
}

export function buildWebhookSummary(config: WebhookConfig): WebhookSummary {
  const total = config.mappings.length;
  const agent = config.mappings.filter((mapping) => mapping.action === 'agent').length;
  const hmac = config.mappings.filter((mapping) => mapping.authMode === 'hmac').length;
  return { total, agent, hmac };
}

export function createAbsoluteHookUrl(hookName: string): string {
  const normalized = hookName.trim();
  if (normalized.length === 0) {
    return `${window.location.origin}/api/hooks/{name}`;
  }
  return `${window.location.origin}/api/hooks/${normalized}`;
}

export function appendEmptyMapping(current: WebhookConfig, emptyMapping: HookMapping): WebhookConfig {
  return {
    ...current,
    mappings: [...current.mappings, emptyMapping],
  };
}

export function replaceMappingAtIndex(current: WebhookConfig, index: number, nextMapping: HookMapping): WebhookConfig {
  return {
    ...current,
    mappings: current.mappings.map((mapping, mappingIndex) => (mappingIndex === index ? nextMapping : mapping)),
  };
}

export function removeMappingAtIndex(current: WebhookConfig, index: number): WebhookConfig {
  return {
    ...current,
    mappings: current.mappings.filter((_, mappingIndex) => mappingIndex !== index),
  };
}

export function shiftEditIndexAfterDelete(currentEditIndex: number | null, deletedIndex: number): number | null {
  if (currentEditIndex == null) {
    return null;
  }
  if (currentEditIndex === deletedIndex) {
    return null;
  }
  if (currentEditIndex > deletedIndex) {
    return currentEditIndex - 1;
  }
  return currentEditIndex;
}
