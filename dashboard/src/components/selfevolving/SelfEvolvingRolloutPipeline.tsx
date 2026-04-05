import type { ReactElement } from 'react';

const STAGE_PIPELINE: ReadonlyArray<{ key: string; label: string }> = [
  { key: 'proposed', label: 'Proposed' },
  { key: 'shadowed', label: 'Shadow' },
  { key: 'canary', label: 'Canary' },
  { key: 'active', label: 'Active' },
];

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

export function resolveNextStageKey(currentStage: string): string | null {
  if (currentStage === 'canary') {
    return 'active';
  }
  if (currentStage === 'shadowed') {
    return 'canary';
  }
  if (currentStage === 'proposed') {
    return 'shadowed';
  }
  return null;
}

export function stageLabel(stageKey: string): string {
  return STAGE_PIPELINE.find((stage) => stage.key === stageKey)?.label ?? stageKey;
}

interface StagePipelineProps {
  currentStage: string;
  nextStage: string | null;
}

function resolveStageClassName(isCurrent: boolean, isNext: boolean, isPast: boolean): string {
  if (isCurrent) {
    return 'badge text-bg-primary';
  }
  if (isNext) {
    return 'badge text-bg-info';
  }
  if (isPast) {
    return 'badge text-bg-success';
  }
  return 'badge text-bg-secondary opacity-60';
}

export function SelfEvolvingRolloutPipeline({ currentStage, nextStage }: StagePipelineProps): ReactElement {
  const currentIndex = STAGE_PIPELINE.findIndex((stage) => stage.key === currentStage);
  return (
    <div className="mb-4">
      <span className="text-xs font-medium uppercase tracking-wider text-muted-foreground mb-2 block">
        Rollout pipeline
      </span>
      <div className="flex items-center gap-1 flex-wrap">
        {STAGE_PIPELINE.map((stage, index) => {
          const isCurrent = stage.key === currentStage;
          const isNext = stage.key === nextStage;
          const isPast = currentIndex >= 0 && index < currentIndex;
          return (
            <span key={stage.key} className="flex items-center gap-1">
              <span className={resolveStageClassName(isCurrent, isNext, isPast)}>{stage.label}</span>
              {index < STAGE_PIPELINE.length - 1 ? (
                <span className="text-xs text-muted-foreground">→</span>
              ) : null}
            </span>
          );
        })}
      </div>
    </div>
  );
}
