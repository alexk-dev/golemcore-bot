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
  planModeActive: boolean;
  activePlanId: string | null;
  plans: PlanSummary[];
}

export async function getPlanControlState(): Promise<PlanControlState> {
  const { data } = await client.get<PlanControlState>('/plans');
  return data;
}

export async function enablePlanMode(payload: { chatId: string; modelTier?: string | null }): Promise<PlanControlState> {
  const { data } = await client.post<PlanControlState>('/plans/mode/on', payload);
  return data;
}

export async function disablePlanMode(): Promise<PlanControlState> {
  const { data } = await client.post<PlanControlState>('/plans/mode/off');
  return data;
}

export async function donePlanMode(): Promise<PlanControlState> {
  const { data } = await client.post<PlanControlState>('/plans/mode/done');
  return data;
}

export async function approvePlan(planId: string): Promise<PlanControlState> {
  const { data } = await client.post<PlanControlState>(`/plans/${encodeURIComponent(planId)}/approve`);
  return data;
}

export async function cancelPlan(planId: string): Promise<PlanControlState> {
  const { data } = await client.post<PlanControlState>(`/plans/${encodeURIComponent(planId)}/cancel`);
  return data;
}

export async function resumePlan(planId: string): Promise<PlanControlState> {
  const { data } = await client.post<PlanControlState>(`/plans/${encodeURIComponent(planId)}/resume`);
  return data;
}
