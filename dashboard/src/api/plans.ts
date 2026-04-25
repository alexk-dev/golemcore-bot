import client from './client';

export interface PlanControlState {
  featureEnabled: boolean;
  sessionId: string | null;
  planModeActive: boolean;
  activePlanId: string | null;
  plans: unknown[];
}

export async function getPlanControlState(sessionId: string): Promise<PlanControlState> {
  const { data } = await client.get<PlanControlState>('/plans', { params: { sessionId } });
  return data;
}

export async function enablePlanMode(payload: {
  sessionId: string;
}): Promise<PlanControlState> {
  const { data } = await client.post<PlanControlState>('/plans/mode/on', payload);
  return data;
}

export async function disablePlanMode(sessionId: string): Promise<PlanControlState> {
  const { data } = await client.post<PlanControlState>('/plans/mode/off', null, { params: { sessionId } });
  return data;
}

export async function donePlanMode(sessionId: string): Promise<PlanControlState> {
  const { data } = await client.post<PlanControlState>('/plans/mode/done', null, { params: { sessionId } });
  return data;
}
