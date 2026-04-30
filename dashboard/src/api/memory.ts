import client from './client';

export type MemoryLayer = 'WORKING' | 'EPISODIC' | 'SEMANTIC' | 'PROCEDURAL';

export type MemoryType =
  | 'DECISION'
  | 'CONSTRAINT'
  | 'FAILURE'
  | 'FIX'
  | 'PREFERENCE'
  | 'PROJECT_FACT'
  | 'TASK_STATE'
  | 'COMMAND_RESULT';

export interface RelevantMemoryItem {
  id: string;
  layer: MemoryLayer | null;
  type: MemoryType | null;
  title: string | null;
  content: string | null;
  scope: string | null;
  tags: string[];
  source: string | null;
  confidence: number | null;
  salience: number | null;
  ttlDays: number | null;
  createdAt: string | null;
  updatedAt: string | null;
  lastAccessedAt: string | null;
  references: string[];
  referenceCount: number;
}

export interface RelevantMemoryResponse {
  items: RelevantMemoryItem[];
  sessionId: string;
  queryText: string;
}

export interface RelevantMemoryParams {
  sessionId: string;
  query?: string;
  limit?: number;
}

export async function getRelevantMemories(params: RelevantMemoryParams): Promise<RelevantMemoryResponse> {
  const { data } = await client.get<RelevantMemoryResponse>('/memory/relevant', {
    params: {
      sessionId: params.sessionId,
      query: params.query,
      limit: params.limit,
    },
  });
  return data;
}
