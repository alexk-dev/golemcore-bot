import { Badge, Form, ProgressBar } from 'react-bootstrap';
import { useContextPanelStore, type FileChangeStat } from '../../store/contextPanelStore';
import PlanControlPanel from './PlanControlPanel';

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

function normalizeFileChanges(input: unknown): FileChangeStat[] {
  if (!Array.isArray(input)) {
    return [];
  }

  const result: FileChangeStat[] = [];
  for (const item of input) {
    if (item == null || typeof item !== 'object') {
      continue;
    }
    const record = item as Record<string, unknown>;
    const path = typeof record.path === 'string' ? record.path : null;
    if (path == null || path.trim().length === 0) {
      continue;
    }

    const addedLines = typeof record.addedLines === 'number' ? record.addedLines : 0;
    const removedLines = typeof record.removedLines === 'number' ? record.removedLines : 0;
    const deleted = record.deleted === true;

    result.push({
      path,
      addedLines,
      removedLines,
      deleted,
    });
  }

  return result;
}

function extractFileName(path: string): string {
  const normalized = path.replace(/\\/g, '/');
  const segments = normalized.split('/');
  const last = segments[segments.length - 1];
  return last.length > 0 ? last : path;
}

function renderFileDelta(change: FileChangeStat): string {
  const added = Math.max(0, change.addedLines);
  const removed = Math.max(0, change.removedLines);
  return `+${added} / -${removed}`;
}

interface FileChangeRowProps {
  change: FileChangeStat;
}

function FileChangeRow({ change }: FileChangeRowProps) {
  const added = Math.max(0, change.addedLines);
  const removed = Math.max(0, change.removedLines);
  const total = added + removed;
  const addPercent = total > 0 ? Math.round((added / total) * 100) : 100;
  const removePercent = Math.max(0, 100 - addPercent);
  const isDeleted = change.deleted || (added === 0 && removed > 0);

  return (
    <div className="file-change-item">
      <div className="file-change-header">
        <span className={`file-change-name ${isDeleted ? 'file-change-name--deleted' : ''}`} title={change.path}>
          {extractFileName(change.path)}
        </span>
        <small className="text-body-secondary">{renderFileDelta(change)}</small>
      </div>
      <div className="file-change-path text-body-secondary">{change.path}</div>
      <div className="file-change-bar" role="img" aria-label={`Added ${added} and removed ${removed} lines`}>
        <div className="file-change-bar-added" style={{ width: `${addPercent}%` }} />
        {removePercent > 0 && <div className="file-change-bar-removed" style={{ width: `${removePercent}%` }} />}
      </div>
    </div>
  );
}

interface Props {
  tier: string;
  tierForce: boolean;
  chatSessionId: string;
  onTierChange: (tier: string) => void;
  onForceChange: (force: boolean) => void;
  forceOpen?: boolean;
}

export default function ContextPanel({ tier, tierForce, chatSessionId, onTierChange, onForceChange, forceOpen }: Props) {
  const { panelOpen, turnMetadata, goals, goalsFeatureEnabled } = useContextPanelStore();

  if (forceOpen !== true && !panelOpen) {return null;}

  const contextPercent =
    turnMetadata.inputTokens != null && turnMetadata.maxContextTokens != null
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
  const fileChanges = normalizeFileChanges(turnMetadata.fileChanges);

  return (
    <div className="context-panel">
      <div className="context-panel-header">
        <div className="context-panel-title">Context</div>
        <small className="text-body-secondary">Live model and token telemetry</small>
      </div>

      {/* MODEL */}
      <div className="context-section">
        <div className="section-label">MODEL</div>
        <div className="section-value">
          <span className="font-mono">
            {turnMetadata.model ?? '--'}
            {turnMetadata.reasoning != null && turnMetadata.reasoning.length > 0 ? `:${turnMetadata.reasoning}` : ''}
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

      <PlanControlPanel chatSessionId={chatSessionId} />

      {/* FILE CHANGES */}
      {fileChanges.length > 0 && (
        <div className="context-section">
          <div className="section-label">EDITED FILES</div>
          <div className="file-change-list">
            {fileChanges.map((change) => (
              <FileChangeRow key={change.path} change={change} />
            ))}
          </div>
        </div>
      )}

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
