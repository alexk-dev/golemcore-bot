import client from './client';

export interface GoalTask {
  id: string;
  title: string;
  status: string;
  order: number;
}

export interface Goal {
  id: string;
  title: string;
  description: string;
  status: string;
  completedTasks: number;
  totalTasks: number;
  tasks: GoalTask[];
}

export interface GoalsResponse {
  featureEnabled: boolean;
  autoModeEnabled: boolean;
  goals: Goal[];
}

export async function getGoals(): Promise<GoalsResponse> {
  const { data } = await client.get('/goals');
  return data;
}
