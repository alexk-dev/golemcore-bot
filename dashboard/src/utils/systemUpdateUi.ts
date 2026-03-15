import type { SystemUpdateStatusResponse } from '../api/system';

export type UpdateBadgeVariant = 'success' | 'warning' | 'danger' | 'secondary' | 'info';
export type UpdateWorkflowStepState = 'complete' | 'current' | 'upcoming' | 'error';
export const AUTO_UPDATE_CHECK_INTERVAL_MS = 4 * 60 * 60 * 1000;

const UPDATE_STATE_LABELS: Record<string, string> = {
  DISABLED: 'Disabled',
  IDLE: 'Idle',
  CHECKING: 'Checking',
  AVAILABLE: 'Available',
  PREPARING: 'Preparing',
  STAGED: 'Staged',
  APPLYING: 'Restarting',
  VERIFYING: 'Reconnecting',
  FAILED: 'Failed',
};

const UPDATE_STATE_DESCRIPTIONS: Record<string, string> = {
  DISABLED: 'Self-update is disabled in backend configuration.',
  IDLE: 'No update workflow is currently running.',
  CHECKING: 'Checking the latest compatible release.',
  AVAILABLE: 'A compatible update is available.',
  PREPARING: 'Downloading and verifying the release package.',
  STAGED: 'The release is staged locally and ready to apply.',
  APPLYING: 'The backend is switching to the new package and restarting.',
  VERIFYING: 'Waiting for the backend to come back after restart.',
  FAILED: 'The last update attempt failed.',
};

const WORKFLOW_STEPS = ['check', 'download', 'stage', 'restart', 'verify'] as const;

const WORKFLOW_STEP_LABELS: Record<(typeof WORKFLOW_STEPS)[number], string> = {
  check: 'Check',
  download: 'Download',
  stage: 'Stage',
  restart: 'Restart',
  verify: 'Verify',
};

const ACTIVE_STEP_BY_STATE: Record<string, (typeof WORKFLOW_STEPS)[number] | null> = {
  DISABLED: null,
  IDLE: null,
  CHECKING: 'check',
  AVAILABLE: 'check',
  PREPARING: 'download',
  STAGED: 'stage',
  APPLYING: 'restart',
  VERIFYING: 'verify',
  FAILED: null,
};

const DERIVED_PROGRESS_BY_STATE: Record<string, number> = {
  DISABLED: 0,
  IDLE: 0,
  CHECKING: 10,
  AVAILABLE: 25,
  PREPARING: 52,
  STAGED: 72,
  APPLYING: 88,
  VERIFYING: 96,
  FAILED: 20,
};

export const UPDATE_BUSY_STATES = new Set<string>(['CHECKING', 'PREPARING', 'APPLYING', 'VERIFYING']);

export interface SidebarUpdateBadge {
  label: string;
  variant: UpdateBadgeVariant;
  text: 'dark' | 'white';
  title: string;
}

export interface TopbarUpdateNotice {
  badge: string;
  tone: 'warning' | 'info' | 'danger';
  title: string;
  busy: boolean;
  emphasis: boolean;
}

export interface BackgroundUpdateCheckStatus {
  enabled: boolean;
  state: string;
  targetVersion: string | null;
  stagedVersion: string | null;
  availableVersion: string | null;
}

export interface UpdateWorkflowStep {
  key: (typeof WORKFLOW_STEPS)[number];
  label: string;
  state: UpdateWorkflowStepState;
}

export interface UpdateWorkflowPresentation {
  title: string;
  description: string;
  progressPercent: number;
  steps: UpdateWorkflowStep[];
}

export function formatUpdateTimestamp(value: string | null | undefined): string {
  if (value == null || value.trim().length === 0) {
    return 'N/A';
  }

  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return value;
  }

  return date.toLocaleString();
}

export function formatVersionLabel(version: string | null | undefined): string {
  if (version == null || version.trim().length === 0) {
    return 'N/A';
  }
  return version;
}

export function getUpdateSourceLabel(source: string | null | undefined): string {
  if (source === 'jar') {
    return 'Local package';
  }
  if (source === 'image') {
    return 'Container image';
  }
  return source == null || source.trim().length === 0 ? 'Unknown source' : source;
}

export function getPrimaryUpdateVersion(status: SystemUpdateStatusResponse): string | null {
  return status.target?.version ?? status.staged?.version ?? status.available?.version ?? null;
}

export function hasPendingUpdate(status: SystemUpdateStatusResponse): boolean {
  return getPrimaryUpdateVersion(status) != null;
}

export function getUpdateActionLabel(status: SystemUpdateStatusResponse): string {
  const version = getPrimaryUpdateVersion(status);
  if (status.state === 'STAGED') {
    return version == null ? 'Restart to apply update' : `Restart to apply ${version}`;
  }
  if (status.state === 'APPLYING' || status.state === 'VERIFYING') {
    return 'Restarting...';
  }
  return version == null ? 'Update now' : `Update to ${version}`;
}

export function getUpdateStateVariant(state: string): UpdateBadgeVariant {
  if (state === 'FAILED') {
    return 'danger';
  }
  if (state === 'AVAILABLE' || state === 'STAGED') {
    return 'warning';
  }
  if (state === 'CHECKING' || state === 'PREPARING' || state === 'APPLYING' || state === 'VERIFYING') {
    return 'info';
  }
  if (state === 'DISABLED') {
    return 'secondary';
  }
  return 'success';
}

export function getUpdateStateLabel(state: string): string {
  return UPDATE_STATE_LABELS[state] ?? state;
}

export function getUpdateStateDescription(state: string): string {
  return UPDATE_STATE_DESCRIPTIONS[state] ?? 'Unknown state';
}

export function getUpdateWorkflowPresentation(status: SystemUpdateStatusResponse): UpdateWorkflowPresentation {
  const activeStep = ACTIVE_STEP_BY_STATE[status.state] ?? null;
  const activeIndex = activeStep == null ? -1 : WORKFLOW_STEPS.indexOf(activeStep);
  const progressPercent = normalizeProgress(status.progressPercent, status.state, status.lastError);
  const failedStep = resolveFailedStep(status);
  const steps = WORKFLOW_STEPS.map((step, index) => ({
    key: step,
    label: WORKFLOW_STEP_LABELS[step],
    state: resolveStepState(status.state, failedStep, index, activeIndex),
  }));

  return {
    title: status.stageTitle?.trim().length ? status.stageTitle : getDefaultWorkflowTitle(status),
    description: status.stageDescription?.trim().length ? status.stageDescription : getUpdateStateDescription(status.state),
    progressPercent,
    steps,
  };
}

export function getSidebarUpdateBadge(state: string): SidebarUpdateBadge | null {
  if (state === 'AVAILABLE') {
    return {
      label: 'NEW',
      variant: 'warning',
      text: 'dark',
      title: 'New update is available',
    };
  }
  if (state === 'STAGED') {
    return {
      label: 'READY',
      variant: 'info',
      text: 'white',
      title: 'Update is staged and ready to apply',
    };
  }
  if (state === 'APPLYING' || state === 'VERIFYING') {
    return {
      label: 'UPD',
      variant: 'info',
      text: 'white',
      title: 'Update is in progress',
    };
  }
  if (state === 'FAILED') {
    return {
      label: 'ERR',
      variant: 'danger',
      text: 'white',
      title: 'Last update operation failed',
    };
  }
  return null;
}

export function getTopbarUpdateNotice(status: SystemUpdateStatusResponse | null | undefined): TopbarUpdateNotice | null {
  if (status == null) {
    return null;
  }

  const version = getPrimaryUpdateVersion(status);

  if (status.state === 'AVAILABLE') {
    return {
      badge: 'NEW',
      tone: 'warning',
      title: version == null ? 'A new update is available' : `Update ${version} is available`,
      busy: false,
      emphasis: true,
    };
  }

  if (status.state === 'STAGED') {
    return {
      badge: 'READY',
      tone: 'info',
      title: version == null ? 'An update is ready to apply' : `Update ${version} is ready to apply`,
      busy: false,
      emphasis: true,
    };
  }

  if (status.state === 'PREPARING' || status.state === 'APPLYING' || status.state === 'VERIFYING') {
    return {
      badge: 'UPD',
      tone: 'info',
      title: version == null ? 'An update is currently in progress' : `Updating to ${version}`,
      busy: true,
      emphasis: false,
    };
  }

  if (status.state === 'FAILED') {
    return {
      badge: 'ERR',
      tone: 'danger',
      title: 'Last update attempt failed',
      busy: false,
      emphasis: true,
    };
  }

  return null;
}

export function shouldCheckSystemUpdateInBackground(
  status: BackgroundUpdateCheckStatus | null,
  lastAttemptAt: number | null,
  now: number,
  isCheckPending: boolean,
): boolean {
  if (status == null || !status.enabled || isCheckPending) {
    return false;
  }

  if (status.state === 'DISABLED' || UPDATE_BUSY_STATES.has(status.state)) {
    return false;
  }

  if (status.targetVersion != null || status.stagedVersion != null || status.availableVersion != null) {
    return false;
  }

  if (lastAttemptAt != null && now - lastAttemptAt < AUTO_UPDATE_CHECK_INTERVAL_MS) {
    return false;
  }

  return true;
}

function normalizeProgress(value: number | null | undefined, state: string, lastError: string | null): number {
  if (typeof value === 'number' && Number.isFinite(value)) {
    return Math.max(0, Math.min(100, Math.round(value)));
  }
  if (state === 'FAILED' && lastError != null && lastError.trim().length > 0) {
    return 52;
  }
  return DERIVED_PROGRESS_BY_STATE[state] ?? 0;
}

function resolveFailedStep(status: SystemUpdateStatusResponse): (typeof WORKFLOW_STEPS)[number] {
  if (status.state !== 'FAILED') {
    return 'check';
  }
  if (status.staged?.version != null) {
    return 'restart';
  }
  if (status.available?.version != null || status.target?.version != null) {
    return 'download';
  }
  return 'check';
}

function resolveStepState(
  state: string,
  failedStep: (typeof WORKFLOW_STEPS)[number],
  index: number,
  activeIndex: number,
): UpdateWorkflowStepState {
  if (state === 'FAILED') {
    if (WORKFLOW_STEPS[index] === failedStep) {
      return 'error';
    }
    return index < WORKFLOW_STEPS.indexOf(failedStep) ? 'complete' : 'upcoming';
  }
  if (activeIndex < 0) {
    return 'upcoming';
  }
  if (index < activeIndex) {
    return 'complete';
  }
  if (index === activeIndex) {
    return 'current';
  }
  return 'upcoming';
}

function getDefaultWorkflowTitle(status: SystemUpdateStatusResponse): string {
  if (status.state === 'IDLE') {
    const currentVersion = formatVersionLabel(status.current?.version);
    return `Running ${currentVersion}`;
  }
  const targetVersion = getPrimaryUpdateVersion(status);
  if (targetVersion != null) {
    return `${getUpdateStateLabel(status.state)} ${targetVersion}`;
  }
  return getUpdateStateLabel(status.state);
}
