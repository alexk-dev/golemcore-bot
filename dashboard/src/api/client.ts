import axios, { type AxiosError, type AxiosRequestConfig, type InternalAxiosRequestConfig } from 'axios';
import { recordTelemetryCounter, recordTelemetryKeyedCounter } from '../lib/telemetry/telemetryBridge';
import { useAuthStore } from '../store/authStore';

interface TelemetryConfigMeta {
  counterKey: string;
  value?: string | null;
}

export interface TelemetryRequestConfig extends AxiosRequestConfig {
  _telemetry?: TelemetryConfigMeta;
}

interface RetryRequestConfig extends InternalAxiosRequestConfig {
  _telemetry?: TelemetryConfigMeta;
  _retry?: boolean;
}

interface RefreshResponse {
  accessToken: string;
}

const client = axios.create({
  baseURL: '/api',
  headers: { 'Content-Type': 'application/json' },
});

// Attach JWT to every request
client.interceptors.request.use((config) => {
  const token = useAuthStore.getState().accessToken;
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

// On 401, try refresh once
client.interceptors.response.use(
  (res) => {
    const telemetry = (res.config as TelemetryRequestConfig)._telemetry;
    if (telemetry != null) {
      if (telemetry.value != null && telemetry.value.length > 0) {
        recordTelemetryKeyedCounter(telemetry.counterKey, telemetry.value);
      } else {
        recordTelemetryCounter(telemetry.counterKey);
      }
    }
    return res;
  },
  async (error: AxiosError) => {
    const original = error.config as RetryRequestConfig | undefined;
    if (error.response?.status === 401 && original != null && original._retry !== true) {
      original._retry = true;
      try {
        const { data } = await axios.post<RefreshResponse>('/api/auth/refresh', {}, { withCredentials: true });
        useAuthStore.getState().setAccessToken(data.accessToken);
        original.headers.Authorization = `Bearer ${data.accessToken}`;
        return client(original);
      } catch {
        useAuthStore.getState().logout();
      }
    }
    throw error;
  }
);

export default client;
