import type { ReactElement } from 'react';
import { Col } from 'react-bootstrap';
import type { HiveStatusResponse } from '../../api/hive';

export interface HiveSessionDetailsProps {
  status: HiveStatusResponse | undefined;
  fallbackServerUrl: string | null;
}

function resolveDetailValue(
  value: string | number | null | undefined,
  fallback: string | number = '?',
): string | number {
  return value ?? fallback;
}

export function HiveSessionDetails({ status, fallbackServerUrl }: HiveSessionDetailsProps): ReactElement {
  const detailRows = [
    ['Server URL', resolveDetailValue(status?.serverUrl, fallbackServerUrl ?? '?')],
    ['Dashboard URL', resolveDetailValue(status?.dashboardBaseUrl)],
    ['SSO enabled', status?.ssoEnabled === true ? 'yes' : 'no'],
    ['Last connected', resolveDetailValue(status?.lastConnectedAt)],
    ['Last heartbeat', resolveDetailValue(status?.lastHeartbeatAt)],
    ['Last token refresh', resolveDetailValue(status?.lastTokenRotatedAt)],
    ['Last control message', resolveDetailValue(status?.controlChannelLastMessageAt)],
    ['Buffered commands', resolveDetailValue(status?.bufferedCommandCount, 0)],
    ['Received commands', resolveDetailValue(status?.receivedCommandCount, 0)],
    ['Last command ID', resolveDetailValue(status?.lastReceivedCommandId)],
  ] as const;

  return (
    <Col md={6}>
      <div className="small text-body-secondary mb-1">Session details</div>
      {detailRows.map(([label, value]) => (
        <div key={label} className="small">{label}: {value}</div>
      ))}
      {status?.controlChannelLastError ? (
        <div className="small text-danger">Control error: {status.controlChannelLastError}</div>
      ) : null}
    </Col>
  );
}
