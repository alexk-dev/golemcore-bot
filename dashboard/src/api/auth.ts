import axios from 'axios';
import client from './client';

export async function getMfaStatus(): Promise<{ mfaRequired: boolean }> {
  const { data } = await axios.get<{ mfaRequired: boolean }>('/api/auth/mfa-status');
  return data;
}

export interface HiveSsoStatus {
  enabled: boolean;
  available: boolean;
  loginUrl: string | null;
  reason: string | null;
}

export async function login(password: string, mfaCode?: string): Promise<{ accessToken: string }> {
  const { data } = await axios.post<{ accessToken: string }>(
    '/api/auth/login',
    { password, mfaCode },
    { withCredentials: true }
  );
  return data;
}

export async function getHiveSsoStatus(): Promise<HiveSsoStatus> {
  const { data } = await axios.get<HiveSsoStatus>('/api/auth/hive/sso-status');
  return data;
}

export async function exchangeHiveSsoCode(code: string, codeVerifier: string): Promise<{ accessToken: string }> {
  const { data } = await axios.post<{ accessToken: string }>(
    '/api/auth/hive/exchange',
    { code, codeVerifier },
    { withCredentials: true }
  );
  return data;
}

export async function logout(): Promise<void> {
  await client.post('/auth/logout', {}, { withCredentials: true });
}

export async function getMe(): Promise<{ username: string; mfaEnabled: boolean }> {
  const { data } = await client.get<{ username: string; mfaEnabled: boolean }>('/auth/me');
  return data;
}

export async function setupMfa(): Promise<{ secret: string; qrUri: string }> {
  const { data } = await client.post<{ secret: string; qrUri: string }>('/auth/mfa/setup');
  return data;
}

export async function enableMfa(secret: string, verificationCode: string): Promise<{ success: boolean }> {
  const { data } = await client.post<{ success: boolean }>('/auth/mfa/enable', { secret, verificationCode });
  return data;
}

export async function disableMfa(password: string): Promise<{ success: boolean }> {
  const { data } = await client.post<{ success: boolean }>('/auth/mfa/disable', { password });
  return data;
}

export async function changePassword(oldPassword: string, newPassword: string): Promise<{ success: boolean }> {
  const { data } = await client.post<{ success: boolean }>('/auth/password', { oldPassword, newPassword });
  return data;
}
