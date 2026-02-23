import client from './client';

export interface PlanSummary {
  id: string;
  title: string | null;
  status: string;
  modelTier: string | null;
  createdAt: string | null;
  updatedAt: string | null;
  stepCount: number;
  completedStepCount: number;
  failedStepCount: number;
  active: boolean;
}

export interface PlanControlState {
  featureEnabled: boolean;
  sessionId: string | null;
  planModeActive: boolean;
  activePlanId: string | null;
  plans: PlanSummary[];
}

export async function getPlanControlState(sessionId: string): Promise<PlanControlState> {
  const { data } = await client.get<PlanControlState>('/plans', { params: { sessionId } });
  return data;
}

export async function enablePlanMode(payload: {
  sessionId: string;
  modelTier?: string | null;
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

export async function approvePlan(planId: string, sessionId: string): Promise<PlanControlState> {
  const { data } = await client.post<PlanControlState>(
    `/plans/${encodeURIComponent(planId)}/approve`,
    null,
    { params: { sessionId } },
  );
  return data;
}

export async function cancelPlan(planId: string, sessionId: string): Promise<PlanControlState> {
  const { data } = await client.post<PlanControlState>(
    `/plans/${encodeURIComponent(planId)}/cancel`,
    null,
    { params: { sessionId } },
  );
  return data;
}

export async function resumePlan(planId: string, sessionId: string): Promise<PlanControlState> {
  const { data } = await client.post<PlanControlState>(
    `/plans/${encodeURIComponent(planId)}/resume`,
    null,
    { params: { sessionId } },
  );
  return data;
}
