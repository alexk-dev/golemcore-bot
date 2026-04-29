import type { CSSProperties } from 'react';
import type { TurnMetadata } from '../../../../store/contextPanelStore';
import { deriveContextUsage, formatTokens } from './contextUsage';

interface InspectorContextCardProps {
  meta: TurnMetadata | undefined;
}

interface ProgressFillStyle extends CSSProperties {
  '--harness-progress-pct': string;
}

export default function InspectorContextCard({ meta }: InspectorContextCardProps) {
  const usage = meta != null ? deriveContextUsage(meta) : null;

  if (usage == null) {
    return (
      <section className="harness-inspector__card" aria-label="Context usage">
        <h3 className="harness-inspector__card-title">Context</h3>
        <p className="harness-inspector__card-label">No telemetry yet</p>
      </section>
    );
  }

  const fillStyle: ProgressFillStyle = { '--harness-progress-pct': `${usage.percentage}%` };

  return (
    <section className="harness-inspector__card" aria-label="Context usage">
      <h3 className="harness-inspector__card-title">Context</h3>
      <div className={`harness-inspector__progress harness-inspector__progress--${usage.state}`}>
        <div className="harness-inspector__card-row">
          <span className="harness-inspector__card-value">{usage.percentage}%</span>
          <span className="harness-inspector__card-label">
            {formatTokens(usage.usedTokens)} / {formatTokens(usage.maxTokens)} tokens
          </span>
        </div>
        <div
          className="harness-inspector__progress-track"
          role="progressbar"
          aria-valuenow={usage.percentage}
          aria-valuemin={0}
          aria-valuemax={100}
          aria-label="Context usage"
        >
          <div className="harness-inspector__progress-fill" style={fillStyle} />
        </div>
      </div>
    </section>
  );
}
