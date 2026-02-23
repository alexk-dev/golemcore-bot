import client from './client';

export interface SessionSummary {
  id: string;
  channelType: string;
  chatId: string;
  conversationKey: string;
  transportChatId: string | null;
  messageCount: number;
  state: string;
  createdAt: string | null;
  updatedAt: string | null;
  title: string | null;
  preview: string | null;
  active: boolean;
}

export interface SessionDetail {
  id: string;
  channelType: string;
  chatId: string;
  conversationKey: string;
  transportChatId: string | null;
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
  model: string | null;
  modelTier: string | null;
}

export interface ActiveSession {
  channelType: string;
  clientInstanceId: string | null;
  transportChatId: string | null;
  conversationKey: string;
  sessionId: string;
  source: string | null;
}

export interface SetActiveSessionRequest {
  channelType: string;
  clientInstanceId: string;
  conversationKey: string;
}

export interface CreateSessionRequest {
  channelType: string;
  clientInstanceId: string;
  conversationKey?: string;
  activate?: boolean;
}

export async function listSessions(channel?: string): Promise<SessionSummary[]> {
  const { data } = await client.get<SessionSummary[]>('/sessions', { params: channel ? { channel } : {} });
  return data;
}

export async function listRecentSessions(
  channel: string,
  clientInstanceId: string,
  limit = 5,
): Promise<SessionSummary[]> {
  const { data } = await client.get<SessionSummary[]>('/sessions/recent', {
    params: { channel, clientInstanceId, limit },
  });
  return data;
}

export async function getActiveSession(channel: string, clientInstanceId: string): Promise<ActiveSession> {
  const { data } = await client.get<ActiveSession>('/sessions/active', { params: { channel, clientInstanceId } });
  return data;
}

export async function setActiveSession(request: SetActiveSessionRequest): Promise<ActiveSession> {
  const { data } = await client.post<ActiveSession>('/sessions/active', request);
  return data;
}

export async function createSession(request: CreateSessionRequest): Promise<SessionSummary> {
  const { data } = await client.post<SessionSummary>('/sessions', request);
  return data;
}

export async function getSession(id: string): Promise<SessionDetail> {
  const { data } = await client.get<SessionDetail>(`/sessions/${encodeURIComponent(id)}`);
  return data;
}

export async function deleteSession(id: string): Promise<void> {
  await client.delete(`/sessions/${encodeURIComponent(id)}`);
}

export async function compactSession(id: string, keepLast = 20): Promise<{ removed: number }> {
  const { data } = await client.post<{ removed: number }>(`/sessions/${encodeURIComponent(id)}/compact`, null, {
    params: { keepLast },
  });
  return data;
}

export async function clearSession(id: string): Promise<void> {
  await client.post(`/sessions/${encodeURIComponent(id)}/clear`);
}
