import React, { type ReactElement, type ReactNode } from 'react';

import { useTelemetry } from '../../lib/telemetry/TelemetryProvider';
import type { UiErrorInput } from '../../lib/telemetry/telemetryTypes';

interface TelemetryErrorBoundaryProps {
  children: ReactNode;
}

interface TelemetryErrorBoundaryInnerProps {
  children: ReactNode;
  onError: (input: UiErrorInput) => void;
}

interface TelemetryErrorBoundaryState {
  hasError: boolean;
}

class TelemetryErrorBoundaryInner extends React.Component<
  TelemetryErrorBoundaryInnerProps,
  TelemetryErrorBoundaryState
> {
  public constructor(props: TelemetryErrorBoundaryInnerProps) {
    super(props);
    this.state = { hasError: false };
  }

  public static getDerivedStateFromError(): TelemetryErrorBoundaryState {
    return { hasError: true };
  }

  public componentDidCatch(error: Error, info: React.ErrorInfo): void {
    this.props.onError({
      route: typeof window !== 'undefined' ? window.location.pathname : null,
      errorName: error.name,
      message: error.message,
      source: 'react',
      componentStack: info.componentStack || null,
    });
  }

  public render(): ReactNode {
    if (this.state.hasError) {
      return null;
    }
    return this.props.children;
  }
}

export function TelemetryErrorBoundary({ children }: TelemetryErrorBoundaryProps): ReactElement {
  const telemetry = useTelemetry();

  return (
    <TelemetryErrorBoundaryInner onError={telemetry.recordUiError}>
      {children}
    </TelemetryErrorBoundaryInner>
  );
}
