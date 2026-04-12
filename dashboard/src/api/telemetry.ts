import type { UiUsageRollup } from '../lib/telemetry/telemetryTypes';

declare function gtag(command: 'event', eventName: string, params: Record<string, unknown>): void;

/**
 * Flush a UI telemetry rollup by sending individual GA4 events via gtag.js.
 * Each counter and error group becomes a separate event so GA4 can group/filter
 * on flat dimensions.
 */
export function postTelemetryRollup(rollup: UiUsageRollup): Promise<void> {
  if (typeof gtag !== 'function') {
    return Promise.resolve();
  }

  const { usage, errors, bucketMinutes } = rollup;

  for (const [counterName, count] of Object.entries(usage.counters)) {
    if (count > 0) {
      gtag('event', 'ui_counter', {
        feature_area: 'ui',
        feature_name: counterName,
        count,
        bucket_minutes: bucketMinutes,
      });
    }
  }

  for (const [counterName, keyed] of Object.entries(usage.byRoute)) {
    for (const [key, count] of Object.entries(keyed)) {
      if (count > 0) {
        gtag('event', 'ui_counter', {
          feature_area: 'ui',
          feature_name: counterName,
          action_route: key,
          count,
          bucket_minutes: bucketMinutes,
        });
      }
    }
  }

  for (const group of errors.groups) {
    if (group.count > 0) {
      gtag('event', 'ui_error', {
        feature_area: 'ui',
        route_group: group.route,
        error_name: group.errorName,
        error_source: group.source,
        count: group.count,
        bucket_minutes: bucketMinutes,
      });
    }
  }

  return Promise.resolve();
}
