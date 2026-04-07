import client from './client';
import type { UiUsageRollup } from '../lib/telemetry/telemetryTypes';

export async function postTelemetryRollup(payload: UiUsageRollup): Promise<void> {
  await client.post('/telemetry/rollups', payload);
}
