import type { TurnMetadata } from '../../../../store/contextPanelStore';

export type ContextUsageState = 'normal' | 'warning' | 'critical';

export interface ContextUsage {
  usedTokens: number;
  maxTokens: number;
  percentage: number;
  state: ContextUsageState;
}

export function deriveContextUsage(meta: TurnMetadata): ContextUsage | null {
  const max = meta.maxContextTokens;
  const used = meta.totalTokens;
  if (max == null || max <= 0 || used == null || used < 0) {
    return null;
  }
  const ratio = used / max;
  const percentage = Math.min(100, Math.round(ratio * 100));
  let state: ContextUsageState = 'normal';
  if (ratio >= 0.85) {
    state = 'critical';
  } else if (ratio >= 0.6) {
    state = 'warning';
  }
  return { usedTokens: used, maxTokens: max, percentage, state };
}

export function formatTokens(value: number): string {
  if (value >= 1000) {
    return `${(value / 1000).toFixed(1)}k`;
  }
  return value.toLocaleString();
}
