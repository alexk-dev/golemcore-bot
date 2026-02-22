export type UpdateBadgeVariant = 'success' | 'warning' | 'danger' | 'secondary' | 'info';

const UPDATE_STATE_LABELS: Record<string, string> = {
  DISABLED: 'Disabled',
  IDLE: 'Idle',
  CHECKING: 'Checking',
  AVAILABLE: 'Available',
  PREPARING: 'Preparing',
  STAGED: 'Staged',
  APPLYING: 'Applying',
  VERIFYING: 'Verifying',
  FAILED: 'Failed',
};

const UPDATE_STATE_DESCRIPTIONS: Record<string, string> = {
  DISABLED: 'Self-update is disabled in backend configuration.',
  IDLE: 'No pending update actions.',
  CHECKING: 'Backend is checking the latest release.',
  AVAILABLE: 'A new release is available for staging.',
  PREPARING: 'Release artifact is being downloaded and verified.',
  STAGED: 'Update is staged locally and ready to apply.',
  APPLYING: 'Restart is scheduled to apply the staged update.',
  VERIFYING: 'Service is restarting and verifying selected version.',
  FAILED: 'The last update action failed. Check the error details below.',
};

export const UPDATE_BUSY_STATES = new Set<string>(['CHECKING', 'PREPARING', 'APPLYING', 'VERIFYING']);

export interface SidebarUpdateBadge {
  label: string;
  variant: UpdateBadgeVariant;
  text: 'dark' | 'white';
  title: string;
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
      label: 'STAGED',
      variant: 'info',
      text: 'white',
      title: 'Update is staged and ready to apply',
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
