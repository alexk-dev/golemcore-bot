import type { HookAction, HookAuthMode } from '../../api/webhooks';

export interface DeliveryChannelOption {
  value: string;
  label: string;
  running: boolean;
  unavailable?: boolean;
}

export const ACTION_DESCRIPTIONS: Record<HookAction, string> = {
  wake: 'Fire-and-forget trigger. Returns 200 immediately.',
  agent: 'Runs a full agent turn. Returns 202 and can deliver callback or channel output.',
};

export const AUTH_DESCRIPTIONS: Record<HookAuthMode, string> = {
  bearer: 'Uses the global webhook bearer token.',
  hmac: 'Validates signed payload using HMAC-SHA256.',
};

export const surfaceClassName = 'rounded-3xl border border-border/80 bg-card/70 p-4 shadow-soft backdrop-blur-sm';
export const fieldLabelClassName = 'mb-2 flex items-center gap-2 text-[0.72rem] font-semibold uppercase tracking-[0.16em] text-muted-foreground';
export const fieldHelpClassName = 'mt-2 text-sm leading-6 text-muted-foreground';
export const controlClassName = 'h-12 rounded-2xl border-border/80 bg-background/80 shadow-none';

export function toNullableString(value: string): string | null {
  const normalized = value.trim();
  return normalized.length > 0 ? normalized : null;
}

export function toNullableTemplate(value: string): string | null {
  return value.length > 0 ? value : null;
}

export function resolveHookAction(value: string): HookAction {
  return value === 'agent' ? 'agent' : 'wake';
}

export function resolveHookAuthMode(value: string): HookAuthMode {
  return value === 'hmac' ? 'hmac' : 'bearer';
}

export function normalizeChannel(value: string | null | undefined): string | null {
  if (value == null) {
    return null;
  }
  const normalized = value.trim().toLowerCase();
  return normalized.length > 0 ? normalized : null;
}

function isBlank(value: string | null | undefined): boolean {
  return value == null || value.trim().length === 0;
}

export function shouldPopulateTelegramTarget(
  deliver: boolean,
  channel: string | null,
  telegramTarget: string | null,
  target: string | null | undefined,
): boolean {
  return deliver && channel === 'telegram' && telegramTarget != null && isBlank(target);
}
