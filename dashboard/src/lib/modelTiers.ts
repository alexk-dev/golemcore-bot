export type ExplicitModelTierId =
  | 'balanced'
  | 'smart'
  | 'deep'
  | 'coding'
  | 'special1'
  | 'special2'
  | 'special3'
  | 'special4'
  | 'special5';

export type DisplayModelTierId = ExplicitModelTierId | 'routing';

export interface ModelTierMeta {
  value: DisplayModelTierId;
  label: string;
  badgeBg: string;
  badgeClassName: string;
  settingsCardColor: string;
  allowsEmptyModelSelection: boolean;
}

export const EXPLICIT_MODEL_TIER_ORDER: ExplicitModelTierId[] = [
  'balanced',
  'smart',
  'deep',
  'coding',
  'special1',
  'special2',
  'special3',
  'special4',
  'special5',
];

export const MODEL_TIER_META: Record<DisplayModelTierId, ModelTierMeta> = {
  routing: { value: 'routing', label: 'Routing', badgeBg: 'dark', badgeClassName: 'text-bg-dark', settingsCardColor: 'dark', allowsEmptyModelSelection: false },
  balanced: { value: 'balanced', label: 'Balanced', badgeBg: 'primary', badgeClassName: 'text-bg-primary', settingsCardColor: 'primary', allowsEmptyModelSelection: false },
  smart: { value: 'smart', label: 'Smart', badgeBg: 'success', badgeClassName: 'text-bg-success', settingsCardColor: 'success', allowsEmptyModelSelection: false },
  deep: { value: 'deep', label: 'Deep', badgeBg: 'warning', badgeClassName: 'text-bg-warning', settingsCardColor: 'warning', allowsEmptyModelSelection: false },
  coding: { value: 'coding', label: 'Coding', badgeBg: 'info', badgeClassName: 'text-bg-info', settingsCardColor: 'info', allowsEmptyModelSelection: false },
  special1: { value: 'special1', label: 'Special 1', badgeBg: 'secondary', badgeClassName: 'text-bg-secondary', settingsCardColor: 'secondary', allowsEmptyModelSelection: true },
  special2: { value: 'special2', label: 'Special 2', badgeBg: 'dark', badgeClassName: 'text-bg-dark', settingsCardColor: 'dark', allowsEmptyModelSelection: true },
  special3: { value: 'special3', label: 'Special 3', badgeBg: 'danger', badgeClassName: 'text-bg-danger', settingsCardColor: 'danger', allowsEmptyModelSelection: true },
  special4: { value: 'special4', label: 'Special 4', badgeBg: 'primary', badgeClassName: 'text-bg-primary', settingsCardColor: 'primary', allowsEmptyModelSelection: true },
  special5: { value: 'special5', label: 'Special 5', badgeBg: 'success', badgeClassName: 'text-bg-success', settingsCardColor: 'success', allowsEmptyModelSelection: true },
};

export interface TierSelectOption {
  value: string;
  label: string;
}

export const DEFAULT_ROUTING_TIER_OPTION: TierSelectOption = {
  value: '',
  label: 'Default routing',
};

export function isExplicitModelTier(value: string | null | undefined): value is ExplicitModelTierId {
  if (value == null) {
    return false;
  }
  return EXPLICIT_MODEL_TIER_ORDER.some((tier) => tier === value);
}

export function normalizeExplicitModelTier(
  value: string | null | undefined,
  fallback: ExplicitModelTierId = 'balanced',
): ExplicitModelTierId {
  const normalized = value?.trim().toLowerCase();
  return normalized != null && isExplicitModelTier(normalized) ? normalized : fallback;
}

export function getModelTierMeta(value: string | null | undefined): ModelTierMeta | null {
  if (value == null) {
    return null;
  }
  const normalized = value.trim().toLowerCase();
  if (normalized === 'routing') {
    return MODEL_TIER_META.routing;
  }
  return isExplicitModelTier(normalized) ? MODEL_TIER_META[normalized] : null;
}

export function getExplicitModelTierOptions(includeDefault = false): TierSelectOption[] {
  const options = EXPLICIT_MODEL_TIER_ORDER.map((tier) => ({
    value: tier,
    label: MODEL_TIER_META[tier].label,
  }));
  return includeDefault ? [DEFAULT_ROUTING_TIER_OPTION, ...options] : options;
}

export function allowsEmptyModelSelection(value: DisplayModelTierId): boolean {
  return MODEL_TIER_META[value].allowsEmptyModelSelection;
}
