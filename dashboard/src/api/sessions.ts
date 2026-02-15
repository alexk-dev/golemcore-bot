import client from './client';

export interface SessionSummary {
  id: string;
  channelType: string;
  chatId: string;
  messageCount: number;
  state: string;
  createdAt: string | null;
  updatedAt: string | null;
}

export interface SessionDetail {
  id: string;
  channelType: string;
  chatId: string;
  state: string;
  createdAt: string | null;
  updatedAt: string | null;
  messages: MessageInfo[];
}

export interface MessageInfo {
  id: string;
  role: string;
  content: string;
  timestamp: string | null;
  hasToolCalls: boolean;
  hasVoice: boolean;
}

export async function listSessions(channel?: string): Promise<SessionSummary[]> {
  const { data } = await client.get('/sessions', { params: channel ? { channel } : {} });
  return data;
}

export async function getSession(id: string): Promise<SessionDetail> {
  const { data } = await client.get(`/sessions/${encodeURIComponent(id)}`);
  return data;
}

export async function deleteSession(id: string) {
  await client.delete(`/sessions/${encodeURIComponent(id)}`);
}

export async function compactSession(id: string, keepLast = 20) {
  const { data } = await client.post(`/sessions/${encodeURIComponent(id)}/compact`, null, {
    params: { keepLast },
  });
  return data as { removed: number };
}

export async function clearSession(id: string) {
  await client.post(`/sessions/${encodeURIComponent(id)}/clear`);
}
