import { type ReactElement, useEffect } from 'react';
import { useLocation } from 'react-router-dom';

import { useTelemetry } from '../../lib/telemetry/TelemetryProvider';
import { sanitizeTelemetryRoute } from '../../lib/telemetry/telemetrySanitizers';

export function TelemetryRouteTracker(): ReactElement | null {
  const location = useLocation();
  const telemetry = useTelemetry();

  useEffect(() => {
    // Track route views once per location change without capturing query or user content.
    telemetry.recordCounterByRoute('route_view_count', sanitizeTelemetryRoute(location.pathname));
  }, [location.pathname, telemetry]);

  return null;
}
