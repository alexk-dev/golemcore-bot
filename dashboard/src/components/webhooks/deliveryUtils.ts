import type { WebhookDeliveryStatus } from '../../api/webhookDeliveries';

export const DEFAULT_DELIVERY_LIMIT = 50;

export const DELIVERY_STATUSES: Array<'ALL' | WebhookDeliveryStatus> = [
  'ALL',
  'PENDING',
  'IN_PROGRESS',
  'SUCCESS',
  'FAILED',
];

export function normalizeDeliveryLimit(input: string): number {
  const parsed = Number.parseInt(input, 10);
  if (!Number.isFinite(parsed)) {
    return DEFAULT_DELIVERY_LIMIT;
  }
  return Math.min(200, Math.max(1, parsed));
}

export function formatWebhookTimestamp(value: string | null): string {
  if (value == null || value.length === 0) {
    return '—';
  }
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return value;
  }
  return date.toLocaleString();
}

export function webhookStatusBadgeVariant(status: string): string {
  if (status === 'SUCCESS') {
    return 'success';
  }
  if (status === 'FAILED') {
    return 'danger';
  }
  if (status === 'IN_PROGRESS') {
    return 'info';
  }
  return 'secondary';
}

export function webhookSourceBadgeVariant(source: string): string {
  if (source === 'test') {
    return 'warning';
  }
  return 'primary';
}
