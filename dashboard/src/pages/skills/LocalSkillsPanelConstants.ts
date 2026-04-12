import { getExplicitModelTierOptions } from '../../lib/modelTiers';

export const MODEL_TIER_OPTIONS = [
  { value: '', label: 'Default routing' },
  ...getExplicitModelTierOptions(),
];
