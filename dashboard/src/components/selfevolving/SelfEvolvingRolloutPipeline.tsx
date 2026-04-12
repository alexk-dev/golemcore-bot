import type { ReactElement } from 'react';

import { getStagePipeline } from './SelfEvolvingRolloutPipelineUtils';

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
  const stagePipeline = getStagePipeline();
  const currentIndex = stagePipeline.findIndex((stage) => stage.key === currentStage);
  return (
    <div className="mb-4">
      <span className="text-xs font-medium uppercase tracking-wider text-muted-foreground mb-2 block">
        Rollout pipeline
      </span>
      <div className="flex items-center gap-1 flex-wrap">
        {stagePipeline.map((stage, index) => {
          const isCurrent = stage.key === currentStage;
          const isNext = stage.key === nextStage;
          const isPast = currentIndex >= 0 && index < currentIndex;
          return (
            <span key={stage.key} className="flex items-center gap-1">
              <span className={resolveStageClassName(isCurrent, isNext, isPast)}>{stage.label}</span>
              {index < stagePipeline.length - 1 ? (
                <span className="text-xs text-muted-foreground">?</span>
              ) : null}
            </span>
          );
        })}
      </div>
    </div>
  );
}
