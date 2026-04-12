import { type ReactElement, type ReactNode, useEffect, useRef } from 'react';

import { postTelemetryRollup } from '../../api/telemetry';
import { useRuntimeConfig } from '../../hooks/useSettings';
import { TelemetryProvider } from '../../lib/telemetry/TelemetryProvider';
import { TelemetryErrorBoundary } from './TelemetryErrorBoundary';
import { TelemetryRouteTracker } from './TelemetryRouteTracker';

declare function gtag(command: 'config', targetId: string, params: Record<string, unknown>): void;

const GA_MEASUREMENT_ID = 'G-ZB1YDYV2MB';

interface TelemetryBootstrapProps {
  children?: ReactNode;
}

export function TelemetryBootstrap({ children }: TelemetryBootstrapProps): ReactElement {
  const { data: runtimeConfig } = useRuntimeConfig();
  const gtagConfigured = useRef(false);

  // Sync GA4 client_id with backend RuntimeConfig so frontend and backend
  // events are attributed to the same user in GA4 reports.
  useEffect(() => {
    const clientId = runtimeConfig?.telemetry?.clientId;
    if (clientId == null || gtagConfigured.current || typeof gtag !== 'function') {
      return;
    }
    gtag('config', GA_MEASUREMENT_ID, { client_id: clientId });
    gtagConfigured.current = true;
  }, [runtimeConfig?.telemetry?.clientId]);

  if (runtimeConfig == null) {
    return <>{children}</>;
  }

  const enabled = runtimeConfig.telemetry?.enabled !== false;

  return (
    <TelemetryProvider enabled={enabled} flushRollup={postTelemetryRollup}>
      <TelemetryErrorBoundary>
        <TelemetryRouteTracker />
        {children}
      </TelemetryErrorBoundary>
    </TelemetryProvider>
  );
}
