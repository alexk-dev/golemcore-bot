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

export async function getSystemHealth(): Promise<SystemHealthResponse> {
  const { data } = await client.get<SystemHealthResponse>('/system/health');
  return data;
}

export async function getSystemDiagnostics(): Promise<SystemDiagnosticsResponse> {
  const { data } = await client.get<SystemDiagnosticsResponse>('/system/diagnostics');
  return data;
}

export async function getBrowserHealth(): Promise<BrowserHealthResponse> {
  const { data } = await client.get<BrowserHealthResponse>('/system/browser/health');
  return data;
}
