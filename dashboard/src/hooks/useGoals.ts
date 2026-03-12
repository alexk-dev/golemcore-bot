import toast from 'react-hot-toast';
import {
  type UseMutationResult,
  type UseQueryResult,
  useMutation,
  useQuery,
  useQueryClient,
} from '@tanstack/react-query';
import {
  createGoal,
  createTask,
  deleteGoal,
  deleteTask,
  getGoals,
  updateGoal,
  updateTask,
  type CreateGoalRequest,
  type CreateTaskRequest,
  type DeleteGoalResponse,
  type DeleteTaskResponse,
  type Goal,
  type GoalTask,
  type GoalsResponse,
  type UpdateGoalRequest,
  type UpdateTaskRequest,
} from '../api/goals';
import { SCHEDULER_QUERY_KEY } from './useScheduler';
import { extractErrorMessage } from '../utils/extractErrorMessage';

export const GOALS_QUERY_KEY = ['goals'] as const;

export function useGoalsState(): UseQueryResult<GoalsResponse, unknown> {
  return useQuery({
    queryKey: GOALS_QUERY_KEY,
    queryFn: getGoals,
    refetchInterval: 15000,
  });
}

async function invalidateAutomationQueries(queryClient: ReturnType<typeof useQueryClient>): Promise<void> {
  await Promise.all([
    queryClient.invalidateQueries({ queryKey: GOALS_QUERY_KEY }),
    queryClient.invalidateQueries({ queryKey: SCHEDULER_QUERY_KEY }),
  ]);
}

export interface UpdateGoalVariables {
  goalId: string;
  request: UpdateGoalRequest;
}

export interface UpdateTaskVariables {
  taskId: string;
  request: UpdateTaskRequest;
}

export function useCreateGoal(): UseMutationResult<Goal, unknown, CreateGoalRequest> {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (request: CreateGoalRequest) => createGoal(request),
    onSuccess: async () => {
      toast.success('Goal created');
      await invalidateAutomationQueries(queryClient);
    },
    onError: (error: unknown) => {
      toast.error(`Failed to create goal: ${extractErrorMessage(error)}`);
    },
  });
}

export function useUpdateGoal(): UseMutationResult<Goal, unknown, UpdateGoalVariables> {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ goalId, request }: UpdateGoalVariables) => updateGoal(goalId, request),
    onSuccess: async () => {
      toast.success('Goal updated');
      await invalidateAutomationQueries(queryClient);
    },
    onError: (error: unknown) => {
      toast.error(`Failed to update goal: ${extractErrorMessage(error)}`);
    },
  });
}

export function useDeleteGoal(): UseMutationResult<DeleteGoalResponse, unknown, string> {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (goalId: string) => deleteGoal(goalId),
    onSuccess: async () => {
      toast.success('Goal deleted');
      await invalidateAutomationQueries(queryClient);
    },
    onError: (error: unknown) => {
      toast.error(`Failed to delete goal: ${extractErrorMessage(error)}`);
    },
  });
}

export function useCreateTask(): UseMutationResult<GoalTask, unknown, CreateTaskRequest> {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (request: CreateTaskRequest) => createTask(request),
    onSuccess: async () => {
      toast.success('Task created');
      await invalidateAutomationQueries(queryClient);
    },
    onError: (error: unknown) => {
      toast.error(`Failed to create task: ${extractErrorMessage(error)}`);
    },
  });
}

export function useUpdateTask(): UseMutationResult<GoalTask, unknown, UpdateTaskVariables> {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ taskId, request }: UpdateTaskVariables) => updateTask(taskId, request),
    onSuccess: async () => {
      toast.success('Task updated');
      await invalidateAutomationQueries(queryClient);
    },
    onError: (error: unknown) => {
      toast.error(`Failed to update task: ${extractErrorMessage(error)}`);
    },
  });
}

export function useDeleteTask(): UseMutationResult<DeleteTaskResponse, unknown, string> {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (taskId: string) => deleteTask(taskId),
    onSuccess: async () => {
      toast.success('Task deleted');
      await invalidateAutomationQueries(queryClient);
    },
    onError: (error: unknown) => {
      toast.error(`Failed to delete task: ${extractErrorMessage(error)}`);
    },
  });
}
