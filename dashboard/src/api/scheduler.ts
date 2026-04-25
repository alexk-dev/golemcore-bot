import type { Goal, GoalTask } from './goals';
import client from './client';

export type SchedulerTargetType = 'GOAL' | 'TASK' | 'SCHEDULED_TASK';
export type SchedulerFrequency = 'daily' | 'weekdays' | 'weekly' | 'custom';
export type SchedulerMode = 'simple' | 'advanced';
export type ScheduledTaskExecutionMode = 'AGENT_PROMPT' | 'SHELL_COMMAND';
export type SchedulerGoal = Goal;
export type SchedulerTask = GoalTask;

export interface SchedulerScheduledTask {
  id: string;
  title: string;
  description: string | null;
  prompt: string | null;
  executionMode: ScheduledTaskExecutionMode;
  shellCommand: string | null;
  shellWorkingDirectory: string | null;
  reflectionModelTier: string | null;
  reflectionTierPriority: boolean;
  legacySourceType: string | null;
  legacySourceId: string | null;
}

export interface SchedulerReport {
  channelType: string;
  chatId: string | null;
  webhookUrl: string | null;
  webhookBearerToken: string | null;
}

export interface SchedulerReportPatch {
  operation: 'SET' | 'CLEAR';
  config: SchedulerReport | null;
}

export interface SchedulerReportChannelOption {
  type: string;
  label: string;
  suggestedChatId: string | null;
}

export interface SchedulerSchedule {
  id: string;
  type: SchedulerTargetType;
  targetId: string;
  targetLabel: string;
  cronExpression: string;
  enabled: boolean;
  clearContextBeforeRun: boolean;
  report: SchedulerReport | null;
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
  standaloneTasks: SchedulerTask[];
  scheduledTasks: SchedulerScheduledTask[];
  schedules: SchedulerSchedule[];
  reportChannelOptions: SchedulerReportChannelOption[];
}

export interface CreateScheduleRequest {
  targetType: SchedulerTargetType;
  targetId: string;
  frequency?: SchedulerFrequency;
  days?: number[];
  time?: string;
  maxExecutions: number | null;
  mode?: SchedulerMode;
  cronExpression?: string;
  clearContextBeforeRun: boolean;
  report?: SchedulerReport | null;
}

export interface UpdateScheduleRequest extends Omit<CreateScheduleRequest, 'report'> {
  enabled: boolean;
  report?: SchedulerReportPatch | null;
}

export interface DeleteScheduleResponse {
  scheduleId: string;
}

export interface SchedulerRunSummary {
  runId: string;
  sessionId: string;
  channelType: string;
  conversationKey: string;
  transportChatId: string | null;
  scheduleId: string | null;
  scheduleTargetType: string | null;
  scheduleTargetId: string | null;
  scheduleTargetLabel: string | null;
  scheduledTaskId: string | null;
  scheduledTaskLabel: string | null;
  goalId: string | null;
  goalLabel: string | null;
  taskId: string | null;
  taskLabel: string | null;
  status: string;
  messageCount: number;
  startedAt: string | null;
  lastActivityAt: string | null;
}

export interface SchedulerRunMessage {
  id: string;
  role: string;
  content: string;
  timestamp: string | null;
  hasToolCalls: boolean;
  hasVoice: boolean;
  model: string | null;
  modelTier: string | null;
  skill: string | null;
}

export interface SchedulerRunDetail extends SchedulerRunSummary {
  messages: SchedulerRunMessage[];
}

export interface SchedulerRunsResponse {
  runs: SchedulerRunSummary[];
}

export async function getSchedulerState(): Promise<SchedulerStateResponse> {
  const { data } = await client.get<SchedulerStateResponse>('/scheduler');
  return data;
}

export async function createSchedule(request: CreateScheduleRequest): Promise<SchedulerSchedule> {
  const { data } = await client.post<SchedulerSchedule>('/scheduler/schedules', request);
  return data;
}

export async function updateSchedule(scheduleId: string, request: UpdateScheduleRequest): Promise<SchedulerSchedule> {
  const { data } = await client.put<SchedulerSchedule>(
    `/scheduler/schedules/${encodeURIComponent(scheduleId)}`,
    request,
  );
  return data;
}

export async function deleteSchedule(scheduleId: string): Promise<DeleteScheduleResponse> {
  const { data } = await client.delete<DeleteScheduleResponse>(`/scheduler/schedules/${encodeURIComponent(scheduleId)}`);
  return data;
}

export async function getSchedulerRuns(scheduleId?: string): Promise<SchedulerRunsResponse> {
  const params = scheduleId != null && scheduleId.length > 0 ? { scheduleId } : undefined;
  const { data } = await client.get<SchedulerRunsResponse>('/scheduler/runs', { params });
  return data;
}

export async function getSchedulerRun(runId: string): Promise<SchedulerRunDetail> {
  const { data } = await client.get<SchedulerRunDetail>(`/scheduler/runs/${encodeURIComponent(runId)}`);
  return data;
}
