import { type ReactElement, useEffect } from 'react';
import { useLocation } from 'react-router-dom';

import { useTelemetry } from '../../lib/telemetry/TelemetryProvider';

export function TelemetryRouteTracker(): ReactElement | null {
  const location = useLocation();
  const telemetry = useTelemetry();

  useEffect(() => {
    // Track route views once per location change without capturing query or user content.
    telemetry.recordCounterByRoute('route_view_count', location.pathname);
  }, [location.pathname, telemetry]);

  return null;
}
