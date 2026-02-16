import client from './client';

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

export async function getSystemDiagnostics(): Promise<SystemDiagnosticsResponse> {
  const { data } = await client.get<SystemDiagnosticsResponse>('/system/diagnostics');
  return data;
}
