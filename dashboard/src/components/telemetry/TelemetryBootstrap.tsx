import type { ReactElement, ReactNode } from 'react';

import { useRuntimeConfig } from '../../hooks/useSettings';
import { TelemetryProvider } from '../../lib/telemetry/TelemetryProvider';
import { TelemetryErrorBoundary } from './TelemetryErrorBoundary';
import { TelemetryRouteTracker } from './TelemetryRouteTracker';

interface TelemetryBootstrapProps {
  children?: ReactNode;
}

export function TelemetryBootstrap({ children }: TelemetryBootstrapProps): ReactElement {
  const { data: runtimeConfig } = useRuntimeConfig();
  if (runtimeConfig == null) {
    return <>{children}</>;
  }

  const enabled = runtimeConfig.telemetry?.enabled !== false;

  return (
    <TelemetryProvider enabled={enabled}>
      <TelemetryErrorBoundary>
        <TelemetryRouteTracker />
        {children}
      </TelemetryErrorBoundary>
    </TelemetryProvider>
  );
}
