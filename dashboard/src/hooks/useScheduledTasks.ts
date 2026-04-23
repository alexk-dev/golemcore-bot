import toast from 'react-hot-toast';
import {
  type UseMutationResult,
  useMutation,
  useQueryClient,
} from '@tanstack/react-query';
import {
  createScheduledTask,
  deleteScheduledTask,
  runScheduledTaskNow,
  updateScheduledTask,
  type CreateScheduledTaskRequest,
  type DeleteScheduledTaskResponse,
  type RunScheduledTaskResponse,
  type UpdateScheduledTaskRequest,
} from '../api/scheduledTasks';
import type { SchedulerScheduledTask } from '../api/scheduler';
import { SCHEDULER_QUERY_KEY } from './useScheduler';
import { extractErrorMessage } from '../utils/extractErrorMessage';

export interface UpdateScheduledTaskVariables {
  scheduledTaskId: string;
  request: UpdateScheduledTaskRequest;
}

export function useCreateScheduledTask(): UseMutationResult<
  SchedulerScheduledTask,
  unknown,
  CreateScheduledTaskRequest
> {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (request: CreateScheduledTaskRequest) => createScheduledTask(request),
    onSuccess: async () => {
      toast.success('Scheduled task created');
      await queryClient.invalidateQueries({ queryKey: SCHEDULER_QUERY_KEY });
    },
    onError: (error: unknown) => {
      toast.error(`Failed to create scheduled task: ${extractErrorMessage(error)}`);
    },
  });
}

export function useUpdateScheduledTask(): UseMutationResult<
  SchedulerScheduledTask,
  unknown,
  UpdateScheduledTaskVariables
> {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ scheduledTaskId, request }: UpdateScheduledTaskVariables) => (
      updateScheduledTask(scheduledTaskId, request)
    ),
    onSuccess: async () => {
      toast.success('Scheduled task updated');
      await queryClient.invalidateQueries({ queryKey: SCHEDULER_QUERY_KEY });
    },
    onError: (error: unknown) => {
      toast.error(`Failed to update scheduled task: ${extractErrorMessage(error)}`);
    },
  });
}

export function useDeleteScheduledTask(): UseMutationResult<
  DeleteScheduledTaskResponse,
  unknown,
  string
> {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (scheduledTaskId: string) => deleteScheduledTask(scheduledTaskId),
    onSuccess: async () => {
      toast.success('Scheduled task deleted');
      await queryClient.invalidateQueries({ queryKey: SCHEDULER_QUERY_KEY });
    },
    onError: (error: unknown) => {
      toast.error(`Failed to delete scheduled task: ${extractErrorMessage(error)}`);
    },
  });
}

export function useRunScheduledTaskNow(): UseMutationResult<
  RunScheduledTaskResponse,
  unknown,
  string
> {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (scheduledTaskId: string) => runScheduledTaskNow(scheduledTaskId),
    onSuccess: async (response) => {
      if (response.outcome === 'EXECUTED') {
        toast.success('Scheduled task run completed');
      } else if (response.outcome === 'FAILED') {
        toast.error('Scheduled task run failed');
      } else {
        toast.error('Scheduled task could not be started');
      }
      await queryClient.invalidateQueries({ queryKey: SCHEDULER_QUERY_KEY });
    },
    onError: (error: unknown) => {
      toast.error(`Failed to run scheduled task: ${extractErrorMessage(error)}`);
    },
  });
}
