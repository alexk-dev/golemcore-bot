import client from './client';

export type GoalStatus = 'ACTIVE' | 'COMPLETED' | 'PAUSED' | 'CANCELLED';
export type GoalTaskStatus = 'PENDING' | 'IN_PROGRESS' | 'COMPLETED' | 'FAILED' | 'SKIPPED';

export interface GoalTask {
  id: string;
  goalId: string | null;
  title: string;
  description: string | null;
  prompt: string | null;
  status: GoalTaskStatus;
  order: number;
  standalone: boolean;
}

export interface Goal {
  id: string;
  title: string;
  description: string | null;
  prompt: string | null;
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

export interface CreateGoalRequest {
  title: string;
  description: string | null;
  prompt: string | null;
}

export interface UpdateGoalRequest {
  title: string;
  description: string | null;
  prompt: string | null;
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
  status?: GoalTaskStatus | null;
}

export interface UpdateTaskRequest {
  title: string;
  description: string | null;
  prompt: string | null;
  status: GoalTaskStatus;
}

export interface DeleteTaskResponse {
  taskId: string;
}

export async function getGoals(): Promise<GoalsResponse> {
  const { data } = await client.get<GoalsResponse>('/goals');
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
