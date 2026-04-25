import client from './client';

export type GoalStatus = 'ACTIVE' | 'COMPLETED' | 'PAUSED' | 'CANCELLED';
export type GoalTaskStatus = 'PENDING' | 'IN_PROGRESS' | 'COMPLETED' | 'FAILED' | 'SKIPPED';

export interface GoalTask {
  id: string;
  goalId: string | null;
  title: string;
  description: string | null;
  prompt: string | null;
  reflectionModelTier: string | null;
  reflectionTierPriority: boolean;
  status: GoalTaskStatus;
  order: number;
  standalone: boolean;
}

export interface Goal {
  id: string;
  title: string;
  description: string | null;
  prompt: string | null;
  reflectionModelTier: string | null;
  reflectionTierPriority: boolean;
  status: GoalStatus;
  completedTasks: number;
  totalTasks: number;
  tasks: GoalTask[];
}

export interface GoalsResponse {
  featureEnabled: boolean;
  autoModeEnabled: boolean;
  goals: Goal[];
  standaloneTasks: GoalTask[];
}

export interface GoalsQueryParams {
  channel?: string;
  conversationKey?: string;
}

export interface CreateGoalRequest {
  title: string;
  description: string | null;
  prompt: string | null;
  reflectionModelTier?: string | null;
  reflectionTierPriority?: boolean | null;
}

export interface UpdateGoalRequest {
  title: string;
  description: string | null;
  prompt: string | null;
  reflectionModelTier?: string | null;
  reflectionTierPriority?: boolean | null;
  status: GoalStatus;
}

export interface DeleteGoalResponse {
  goalId: string;
}

export interface CreateTaskRequest {
  goalId: string | null;
  title: string;
  description: string | null;
  prompt: string | null;
  reflectionModelTier?: string | null;
  reflectionTierPriority?: boolean | null;
  status?: GoalTaskStatus | null;
}

export interface UpdateTaskRequest {
  title: string;
  description: string | null;
  prompt: string | null;
  reflectionModelTier?: string | null;
  reflectionTierPriority?: boolean | null;
  status: GoalTaskStatus;
}

export interface DeleteTaskResponse {
  taskId: string;
}

export async function getGoals(params?: GoalsQueryParams): Promise<GoalsResponse> {
  const { data } = params == null
    ? await client.get<GoalsResponse>('/goals')
    : await client.get<GoalsResponse>('/goals', { params });
  return data;
}

export async function createGoal(request: CreateGoalRequest): Promise<Goal> {
  const { data } = await client.post<Goal>('/goals', request);
  return data;
}

export async function updateGoal(goalId: string, request: UpdateGoalRequest): Promise<Goal> {
  const { data } = await client.put<Goal>(`/goals/${encodeURIComponent(goalId)}`, request);
  return data;
}

export async function deleteGoal(goalId: string): Promise<DeleteGoalResponse> {
  const { data } = await client.delete<DeleteGoalResponse>(`/goals/${encodeURIComponent(goalId)}`);
  return data;
}

export async function createTask(request: CreateTaskRequest): Promise<GoalTask> {
  const { data } = await client.post<GoalTask>('/tasks', request);
  return data;
}

export async function updateTask(taskId: string, request: UpdateTaskRequest): Promise<GoalTask> {
  const { data } = await client.put<GoalTask>(`/tasks/${encodeURIComponent(taskId)}`, request);
  return data;
}

export async function deleteTask(taskId: string): Promise<DeleteTaskResponse> {
  const { data } = await client.delete<DeleteTaskResponse>(`/tasks/${encodeURIComponent(taskId)}`);
  return data;
}
