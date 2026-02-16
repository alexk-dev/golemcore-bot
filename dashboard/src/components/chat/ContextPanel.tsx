import { Badge, Form, ProgressBar } from 'react-bootstrap';
import { useContextPanelStore } from '../../store/contextPanelStore';

const TIER_COLORS: Record<string, string> = {
  balanced: 'secondary',
  smart: 'primary',
  coding: 'success',
  deep: 'warning',
};

function formatNumber(n: number | null): string {
  if (n == null) {return '--';}
  return n.toLocaleString();
}

interface Props {
  tier: string;
  tierForce: boolean;
  onTierChange: (tier: string) => void;
  onForceChange: (force: boolean) => void;
}

export default function ContextPanel({ tier, tierForce, onTierChange, onForceChange }: Props) {
  const { panelOpen, turnMetadata, goals, goalsFeatureEnabled } = useContextPanelStore();

  if (!panelOpen) {return null;}

  const contextPercent =
    turnMetadata.inputTokens != null && turnMetadata.maxContextTokens
      ? Math.min(100, Math.round((turnMetadata.inputTokens / turnMetadata.maxContextTokens) * 100))
      : null;

  const contextVariant =
    contextPercent == null
      ? 'secondary'
      : contextPercent < 60
        ? 'success'
        : contextPercent < 85
          ? 'warning'
          : 'danger';

  const activeGoals = goals.filter((g) => g.status === 'ACTIVE');

  return (
    <div className="context-panel">
      {/* MODEL */}
      <div className="context-section">
        <div className="section-label">MODEL</div>
        <div className="section-value">
          <span className="font-mono">
            {turnMetadata.model ?? '--'}
            {turnMetadata.reasoning ? `:${turnMetadata.reasoning}` : ''}
          </span>
        </div>
        <div className="d-flex align-items-center gap-2 mt-1">
          <Badge bg={TIER_COLORS[turnMetadata.tier ?? tier] ?? 'secondary'}>
            {(turnMetadata.tier ?? tier).toUpperCase()}
          </Badge>
          {turnMetadata.latencyMs != null && (
            <small className="text-body-secondary">{turnMetadata.latencyMs}ms</small>
          )}
        </div>
      </div>

      {/* TOKENS */}
      <div className="context-section">
        <div className="section-label">TOKENS</div>
        <div className="token-grid">
          <span className="text-body-secondary">In</span>
          <span className="section-value">{formatNumber(turnMetadata.inputTokens)}</span>
          <span className="text-body-secondary">Out</span>
          <span className="section-value">{formatNumber(turnMetadata.outputTokens)}</span>
          <span className="text-body-secondary">Total</span>
          <span className="section-value">{formatNumber(turnMetadata.totalTokens)}</span>
        </div>
      </div>

      {/* CONTEXT */}
      <div className="context-section">
        <div className="section-label">CONTEXT</div>
        <ProgressBar
          now={contextPercent ?? 0}
          variant={contextVariant}
          className="context-meter"
        />
        <div className="d-flex justify-content-between mt-1">
          <small className="text-body-secondary">
            {formatNumber(turnMetadata.inputTokens)} / {formatNumber(turnMetadata.maxContextTokens)}
          </small>
          <small className="text-body-secondary">
            {contextPercent != null ? `${contextPercent}%` : '--'}
          </small>
        </div>
      </div>

      {/* TIER SELECTOR */}
      <div className="context-section">
        <div className="section-label">TIER</div>
        <Form.Select
          size="sm"
          value={tier}
          onChange={(e) => onTierChange(e.target.value)}
        >
          <option value="balanced">Balanced</option>
          <option value="smart">Smart</option>
          <option value="coding">Coding</option>
          <option value="deep">Deep</option>
        </Form.Select>
        <Form.Check
          type="switch"
          label={<small className="text-body-secondary">Force tier</small>}
          checked={tierForce}
          onChange={(e) => onForceChange(e.target.checked)}
          className="mt-2"
        />
      </div>

      {/* GOALS */}
      {goalsFeatureEnabled && activeGoals.length > 0 && (
        <div className="context-section">
          <div className="section-label">GOALS</div>
          {activeGoals.map((goal) => (
            <div key={goal.id} className="goal-item">
              <div className="d-flex justify-content-between align-items-center">
                <strong className="goal-title">{goal.title}</strong>
                <small className="text-body-secondary">
                  {goal.completedTasks}/{goal.totalTasks}
                </small>
              </div>
              {goal.tasks.length > 0 && (
                <ul className="task-list">
                  {goal.tasks.map((task) => (
                    <li key={task.id} className={`task-item ${task.status.toLowerCase()}`}>
                      <span className={`task-status-icon ${task.status.toLowerCase()}`} />
                      <span className={task.status === 'COMPLETED' ? 'text-decoration-line-through text-body-secondary' : ''}>
                        {task.title}
                      </span>
                    </li>
                  ))}
                </ul>
              )}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
