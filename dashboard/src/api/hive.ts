import client from './client';

export interface HiveStatusResponse {
  state: string;
  enabled: boolean;
  managedByProperties: boolean;
  managedJoinCodeAvailable: boolean;
  autoConnect: boolean;
  serverUrl: string | null;
  displayName: string | null;
  hostLabel: string | null;
  sessionPresent: boolean;
  golemId: string | null;
  controlChannelUrl: string | null;
  heartbeatIntervalSeconds: number | null;
  lastConnectedAt: string | null;
  lastHeartbeatAt: string | null;
  lastTokenRotatedAt: string | null;
  controlChannelState: string | null;
  controlChannelConnectedAt: string | null;
  controlChannelLastMessageAt: string | null;
  controlChannelLastError: string | null;
  lastReceivedCommandId: string | null;
  lastReceivedCommandAt: string | null;
  receivedCommandCount: number;
  bufferedCommandCount: number;
  lastError: string | null;
}

export interface JoinHiveRequest {
  joinCode: string | null;
}

export async function getHiveStatus(): Promise<HiveStatusResponse> {
  const { data } = await client.get<HiveStatusResponse>('/hive/status');
  return data;
}

export async function joinHive(request: JoinHiveRequest): Promise<HiveStatusResponse> {
  const { data } = await client.post<HiveStatusResponse>('/hive/join', request);
  return data;
}

export async function reconnectHive(): Promise<HiveStatusResponse> {
  const { data } = await client.post<HiveStatusResponse>('/hive/reconnect');
  return data;
}

export async function leaveHive(): Promise<HiveStatusResponse> {
  const { data } = await client.post<HiveStatusResponse>('/hive/leave');
  return data;
}
