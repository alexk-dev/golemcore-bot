const STAGE_PIPELINE: ReadonlyArray<{ key: string; label: string }> = [
  { key: 'proposed', label: 'Proposed' },
  { key: 'shadowed', label: 'Shadow' },
  { key: 'canary', label: 'Canary' },
  { key: 'active', label: 'Active' },
];

export function getStagePipeline(): ReadonlyArray<{ key: string; label: string }> {
  return STAGE_PIPELINE;
}

export function resolveCurrentStageKey(status: string): string {
  if (status === 'shadowed') {
    return 'shadowed';
  }
  if (status === 'canary') {
    return 'canary';
  }
  if (status === 'active') {
    return 'active';
  }
  return 'proposed';
}

export function resolveNextStageKey(
  currentStage: string,
  shadowRequired: boolean,
  canaryRequired: boolean,
): string | null {
  if (currentStage === 'active' || currentStage === 'reverted') {
    return null;
  }
  if (currentStage === 'canary') {
    return 'active';
  }
  if (currentStage === 'shadowed') {
    return canaryRequired ? 'canary' : 'active';
  }
  if (shadowRequired) {
    return 'shadowed';
  }
  if (canaryRequired) {
    return 'canary';
  }
  return 'active';
}

export function stageLabel(stageKey: string): string {
  return STAGE_PIPELINE.find((stage) => stage.key === stageKey)?.label ?? stageKey;
}
