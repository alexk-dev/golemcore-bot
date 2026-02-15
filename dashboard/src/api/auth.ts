import axios from 'axios';
import client from './client';

export async function getMfaStatus(): Promise<{ mfaRequired: boolean }> {
  const { data } = await axios.get('/api/auth/mfa-status');
  return data;
}

export async function login(password: string, mfaCode?: string) {
  const { data } = await axios.post('/api/auth/login', { password, mfaCode }, { withCredentials: true });
  return data as { accessToken: string };
}

export async function logout() {
  await client.post('/auth/logout', {}, { withCredentials: true });
}

export async function getMe() {
  const { data } = await client.get('/auth/me');
  return data as { username: string; mfaEnabled: boolean };
}

export async function setupMfa() {
  const { data } = await client.post('/auth/mfa/setup');
  return data as { secret: string; qrUri: string };
}

export async function enableMfa(secret: string, verificationCode: string) {
  const { data } = await client.post('/auth/mfa/enable', { secret, verificationCode });
  return data as { success: boolean };
}

export async function disableMfa(password: string) {
  const { data } = await client.post('/auth/mfa/disable', { password });
  return data as { success: boolean };
}

export async function changePassword(oldPassword: string, newPassword: string) {
  const { data } = await client.post('/auth/password', { oldPassword, newPassword });
  return data as { success: boolean };
}
