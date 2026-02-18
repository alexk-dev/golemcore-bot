import axios, { type AxiosError, type InternalAxiosRequestConfig } from 'axios';
import { useAuthStore } from '../store/authStore';

interface RetryRequestConfig extends InternalAxiosRequestConfig {
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
  (res) => res,
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
