import type { ReactElement } from 'react';
import { Alert, Badge, ProgressBar } from 'react-bootstrap';
import type { SystemUpdateStatusResponse } from '../../api/system';
import {
  formatUpdateTimestamp,
  formatVersionLabel,
  getPrimaryUpdateVersion,
  getUpdateSourceLabel,
  getUpdateStateLabel,
  getUpdateStateVariant,
  getUpdateWorkflowPresentation,
} from '../../utils/systemUpdateUi';

interface UpdateWorkflowPanelProps {
  status: SystemUpdateStatusResponse;
  isAutoReloadActive: boolean;
  hasSeenDowntime: boolean;
  lastProbeError: string | null;
}

interface UpdateMetaItemProps {
  label: string;
  value: string;
}

function UpdateMetaItem({ label, value }: UpdateMetaItemProps): ReactElement {
  return (
    <div className="update-meta-item">
      <span className="update-meta-label">{label}</span>
      <span className="update-meta-value">{value}</span>
    </div>
  );
}

export function UpdateWorkflowPanel({
  status,
  isAutoReloadActive,
  hasSeenDowntime,
  lastProbeError,
}: UpdateWorkflowPanelProps): ReactElement {
  const workflow = getUpdateWorkflowPresentation(status);
  const targetVersion = getPrimaryUpdateVersion(status);
  const currentVersion = formatVersionLabel(status.current?.version);
  const currentSource = getUpdateSourceLabel(status.current?.source);

  return (
    <div className="update-workflow-panel">
      <div className="update-workflow-head">
        <div>
          <div className="d-flex align-items-center gap-2 mb-2">
            <Badge bg={getUpdateStateVariant(status.state)}>{getUpdateStateLabel(status.state)}</Badge>
            <span className="update-progress-value">{workflow.progressPercent}%</span>
          </div>
          <div className="update-workflow-title">{workflow.title}</div>
          <div className="update-workflow-description">{workflow.description}</div>
        </div>
      </div>

      <ProgressBar now={workflow.progressPercent} className="update-progress-bar mb-3" aria-label="Update progress" />

      <div className="update-step-list" aria-label="Update workflow steps">
        {workflow.steps.map((step) => (
          <div key={step.key} className={`update-step update-step--${step.state}`}>
            <span className="update-step-dot" aria-hidden="true" />
            <span className="update-step-label">{step.label}</span>
          </div>
        ))}
      </div>

      <div className="update-meta-grid">
        <UpdateMetaItem label="Current" value={currentVersion} />
        <UpdateMetaItem label="Current source" value={currentSource} />
        <UpdateMetaItem label="Target" value={formatVersionLabel(targetVersion)} />
        <UpdateMetaItem label="Staged" value={formatVersionLabel(status.staged?.version)} />
        <UpdateMetaItem label="Available" value={formatVersionLabel(status.available?.version)} />
        <UpdateMetaItem label="Last check" value={formatUpdateTimestamp(status.lastCheckAt)} />
      </div>

      {isAutoReloadActive && (
        <Alert variant="info" className="mb-0 small update-reload-alert">
          {hasSeenDowntime
            ? 'Backend restart detected. Reloading this page as soon as the updated runtime responds.'
            : 'Auto-reload is armed. This page will refresh when the updated backend is ready.'}
          {lastProbeError != null && lastProbeError.trim().length > 0 && (
            <span className="d-block mt-1 text-body-secondary">Last probe: {lastProbeError}</span>
          )}
        </Alert>
      )}
    </div>
  );
}
