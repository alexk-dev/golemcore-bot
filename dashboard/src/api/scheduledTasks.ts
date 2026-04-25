import type {
  ScheduledTaskExecutionMode,
  SchedulerScheduledTask,
} from './scheduler';
import client from './client';

export interface CreateScheduledTaskRequest {
  title: string;
  description?: string | null;
  prompt?: string | null;
  executionMode?: ScheduledTaskExecutionMode;
  shellCommand?: string | null;
  shellWorkingDirectory?: string | null;
  reflectionModelTier?: string | null;
  reflectionTierPriority?: boolean;
}

export interface UpdateScheduledTaskRequest {
  title: string;
  description?: string | null;
  prompt?: string | null;
  executionMode?: ScheduledTaskExecutionMode;
  shellCommand?: string | null;
  shellWorkingDirectory?: string | null;
  reflectionModelTier?: string | null;
  reflectionTierPriority?: boolean;
}

export interface DeleteScheduledTaskResponse {
  scheduledTaskId: string;
}

export interface RunScheduledTaskResponse {
  scheduledTaskId: string;
  outcome: 'EXECUTED' | 'FAILED' | 'SKIPPED_TARGET_MISSING';
}

export async function createScheduledTask(
  request: CreateScheduledTaskRequest,
): Promise<SchedulerScheduledTask> {
  const { data } = await client.post<SchedulerScheduledTask>('/scheduled-tasks', request);
  return data;
}

export async function updateScheduledTask(
  scheduledTaskId: string,
  request: UpdateScheduledTaskRequest,
): Promise<SchedulerScheduledTask> {
  const { data } = await client.put<SchedulerScheduledTask>(
    `/scheduled-tasks/${encodeURIComponent(scheduledTaskId)}`,
    request,
  );
  return data;
}

export async function deleteScheduledTask(
  scheduledTaskId: string,
): Promise<DeleteScheduledTaskResponse> {
  const { data } = await client.delete<DeleteScheduledTaskResponse>(
    `/scheduled-tasks/${encodeURIComponent(scheduledTaskId)}`,
  );
  return data;
}

export async function runScheduledTaskNow(
  scheduledTaskId: string,
): Promise<RunScheduledTaskResponse> {
  const { data } = await client.post<RunScheduledTaskResponse>(
    `/scheduled-tasks/${encodeURIComponent(scheduledTaskId)}/run`,
  );
  return data;
}
