import { type ReactElement, type ReactNode, useEffect, useRef } from 'react';
import { useLocation } from 'react-router-dom';

import { postTelemetryRollup } from '../../api/telemetry';
import { useRuntimeConfig } from '../../hooks/useSettings';
import { TelemetryProvider } from '../../lib/telemetry/TelemetryProvider';
import { sanitizeTelemetryRoute } from '../../lib/telemetry/telemetrySanitizers';
import { TelemetryErrorBoundary } from './TelemetryErrorBoundary';
import { TelemetryRouteTracker } from './TelemetryRouteTracker';

declare function gtag(command: 'config' | 'event', targetOrEventName: string, params: Record<string, unknown>): void;

const GA_MEASUREMENT_ID = 'G-ZB1YDYV2MB';

interface TelemetryBootstrapProps {
  children?: ReactNode;
}

export function TelemetryBootstrap({ children }: TelemetryBootstrapProps): ReactElement {
  const { data: runtimeConfig } = useRuntimeConfig();
  const location = useLocation();
  const configuredClientId = useRef<string | null>(null);
  const lastPageViewRoute = useRef<string | null>(null);
  const telemetryConfig = runtimeConfig?.telemetry;
  const telemetryClientId = telemetryConfig?.clientId;
  const enabled = telemetryConfig?.enabled !== false;

  // Configure GA only after the backend client_id is available, then send page views manually.
  useEffect(() => {
    if (!enabled || telemetryClientId == null || telemetryClientId.trim().length === 0
      || typeof gtag !== 'function') {
      return;
    }
    if (configuredClientId.current !== telemetryClientId) {
      gtag('config', GA_MEASUREMENT_ID, {
        client_id: telemetryClientId,
        send_page_view: false,
      });
      configuredClientId.current = telemetryClientId;
      lastPageViewRoute.current = null;
    }

    const route = sanitizeTelemetryRoute(location.pathname);
    if (lastPageViewRoute.current === route) {
      return;
    }
    gtag('event', 'page_view', {
      page_location: `${window.location.origin}${route}`,
      page_title: document.title,
    });
    lastPageViewRoute.current = route;
  }, [enabled, location.pathname, telemetryClientId]);

  if (runtimeConfig == null) {
    return <>{children}</>;
  }

  return (
    <TelemetryProvider enabled={enabled} flushRollup={postTelemetryRollup}>
      <TelemetryErrorBoundary>
        <TelemetryRouteTracker />
        {children}
      </TelemetryErrorBoundary>
    </TelemetryProvider>
  );
}
