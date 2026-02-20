import client from './client';

export interface SystemHealthResponse {
  status: string;
  version: string;
  gitCommit: string | null;
  buildTime: string | null;
  uptimeMs: number;
  channels: Record<string, { type: string; running: boolean; enabled: boolean }>;
}

export interface SystemDiagnosticsResponse {
  storage: {
    configuredBasePath: string;
    resolvedBasePath: string;
    sessionsFiles: number;
    usageFiles: number;
  };
  environment: {
    STORAGE_PATH: string | null;
    TOOLS_WORKSPACE: string | null;
    SPRING_PROFILES_ACTIVE: string | null;
  };
  runtime: {
    userDir: string;
    userHome: string;
  };
}

export interface BrowserHealthResponse {
  enabled: boolean;
  type: string;
  provider: string;
  headless: boolean;
  timeoutMs: number;
  availableBefore: boolean;
  availableAfter: boolean;
  ok: boolean;
  message: string;
}

export interface LogEntryResponse {
  seq: number;
  timestamp: string;
  level: string;
  logger: string | null;
  thread: string | null;
  message: string | null;
  exception: string | null;
}

export interface LogsPageResponse {
  items: LogEntryResponse[];
  oldestSeq: number | null;
  newestSeq: number | null;
  hasMore: boolean;
}

export interface SystemUpdateVersionInfo {
  version: string;
  source?: string;
  tag?: string;
  assetName?: string;
  preparedAt?: string;
  publishedAt?: string;
}

export interface SystemUpdateStatusResponse {
  state: string;
  enabled: boolean;
  current: SystemUpdateVersionInfo | null;
  staged: SystemUpdateVersionInfo | null;
  available: SystemUpdateVersionInfo | null;
  lastCheckAt: string | null;
  lastError: string | null;
}

export interface SystemUpdateIntentResponse {
  operation: string;
  targetVersion: string | null;
  confirmToken: string;
  expiresAt: string;
}

export interface SystemUpdateActionResponse {
  success: boolean;
  message: string;
  version?: string | null;
}

export interface SystemUpdateHistoryItem {
  operation: string;
  version: string | null;
  timestamp: string;
  result: string;
  message: string | null;
}

export interface ConfirmTokenRequest {
  confirmToken: string;
}

export interface RollbackIntentRequest {
  version?: string;
}

export interface RollbackConfirmRequest extends ConfirmTokenRequest {
  version?: string;
}

export async function getSystemHealth(): Promise<SystemHealthResponse> {
  const { data } = await client.get<SystemHealthResponse>('/system/health');
  return data;
}

export async function getSystemDiagnostics(): Promise<SystemDiagnosticsResponse> {
  const { data } = await client.get<SystemDiagnosticsResponse>('/system/diagnostics');
  return data;
}

export async function getSystemUpdateStatus(): Promise<SystemUpdateStatusResponse> {
  const { data } = await client.get<SystemUpdateStatusResponse>('/system/update/status');
  return data;
}

export async function checkSystemUpdate(): Promise<SystemUpdateActionResponse> {
  const { data } = await client.post<SystemUpdateActionResponse>('/system/update/check');
  return data;
}

export async function prepareSystemUpdate(): Promise<SystemUpdateActionResponse> {
  const { data } = await client.post<SystemUpdateActionResponse>('/system/update/prepare');
  return data;
}

export async function createSystemUpdateApplyIntent(): Promise<SystemUpdateIntentResponse> {
  const { data } = await client.post<SystemUpdateIntentResponse>('/system/update/apply-intent');
  return data;
}

export async function applySystemUpdate(payload: ConfirmTokenRequest): Promise<SystemUpdateActionResponse> {
  const { data } = await client.post<SystemUpdateActionResponse>('/system/update/apply', payload);
  return data;
}

export async function createSystemUpdateRollbackIntent(payload?: RollbackIntentRequest): Promise<SystemUpdateIntentResponse> {
  const { data } = await client.post<SystemUpdateIntentResponse>('/system/update/rollback-intent', payload ?? {});
  return data;
}

export async function rollbackSystemUpdate(payload: RollbackConfirmRequest): Promise<SystemUpdateActionResponse> {
  const { data } = await client.post<SystemUpdateActionResponse>('/system/update/rollback', payload);
  return data;
}

export async function getSystemUpdateHistory(): Promise<SystemUpdateHistoryItem[]> {
  const { data } = await client.get<SystemUpdateHistoryItem[]>('/system/update/history');
  return data;
}

export async function getBrowserHealth(): Promise<BrowserHealthResponse> {
  const { data } = await client.get<BrowserHealthResponse>('/system/browser/health');
  return data;
}

export async function getSystemLogs(params?: { beforeSeq?: number; limit?: number }): Promise<LogsPageResponse> {
  const { data } = await client.get<LogsPageResponse>('/system/logs', { params });
  return data;
}
