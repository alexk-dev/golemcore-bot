import client from './client';

export type SchedulerTargetType = 'GOAL' | 'TASK';
export type SchedulerFrequency = 'daily' | 'weekdays' | 'weekly' | 'custom';

export interface SchedulerTask {
  id: string;
  title: string;
  status: string;
}

export interface SchedulerGoal {
  id: string;
  title: string;
  status: string;
  tasks: SchedulerTask[];
}

export interface SchedulerSchedule {
  id: string;
  type: SchedulerTargetType;
  targetId: string;
  targetLabel: string;
  cronExpression: string;
  enabled: boolean;
  maxExecutions: number;
  executionCount: number;
  createdAt: string | null;
  updatedAt: string | null;
  lastExecutedAt: string | null;
  nextExecutionAt: string | null;
}

export interface SchedulerStateResponse {
  featureEnabled: boolean;
  autoModeEnabled: boolean;
  goals: SchedulerGoal[];
  schedules: SchedulerSchedule[];
}

export interface CreateScheduleRequest {
  targetType: SchedulerTargetType;
  targetId: string;
  frequency: SchedulerFrequency;
  days: number[];
  time: string;
  maxExecutions: number | null;
}

export interface DeleteScheduleResponse {
  scheduleId: string;
}

export async function getSchedulerState(): Promise<SchedulerStateResponse> {
  const { data } = await client.get<SchedulerStateResponse>('/scheduler');
  return data;
}

export async function createSchedule(request: CreateScheduleRequest): Promise<SchedulerSchedule> {
  const { data } = await client.post<SchedulerSchedule>('/scheduler/schedules', request);
  return data;
}

export async function deleteSchedule(scheduleId: string): Promise<DeleteScheduleResponse> {
  const { data } = await client.delete<DeleteScheduleResponse>(`/scheduler/schedules/${encodeURIComponent(scheduleId)}`);
  return data;
}
