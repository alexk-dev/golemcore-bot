import { useMemo } from 'react';
import toast from 'react-hot-toast';
import {
  type UseMutationResult,
  type UseQueryResult,
  useMutation,
  useQuery,
  useQueryClient,
} from '@tanstack/react-query';
import {
  createSchedule,
  deleteSchedule,
  getSchedulerState,
  type CreateScheduleRequest,
  type DeleteScheduleResponse,
  type SchedulerSchedule,
  type SchedulerStateResponse,
} from '../api/scheduler';
import { extractErrorMessage } from '../utils/extractErrorMessage';

const SCHEDULER_QUERY_KEY = ['scheduler'] as const;

export function useSchedulerState(): UseQueryResult<SchedulerStateResponse, unknown> {
  return useQuery({
    queryKey: SCHEDULER_QUERY_KEY,
    queryFn: getSchedulerState,
    refetchInterval: 15000,
  });
}

export function useCreateSchedule(): UseMutationResult<SchedulerSchedule, unknown, CreateScheduleRequest> {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (request: CreateScheduleRequest) => createSchedule(request),
    onSuccess: async () => {
      toast.success('Schedule created');
      await queryClient.invalidateQueries({ queryKey: SCHEDULER_QUERY_KEY });
    },
    onError: (error: unknown) => {
      toast.error(`Failed to create schedule: ${extractErrorMessage(error)}`);
    },
  });
}

export function useDeleteSchedule(): UseMutationResult<DeleteScheduleResponse, unknown, string> {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (scheduleId: string) => deleteSchedule(scheduleId),
    onSuccess: async () => {
      toast.success('Schedule deleted');
      await queryClient.invalidateQueries({ queryKey: SCHEDULER_QUERY_KEY });
    },
    onError: (error: unknown) => {
      toast.error(`Failed to delete schedule: ${extractErrorMessage(error)}`);
    },
  });
}

export function useSchedulerBusyState(items: Array<{ isPending?: boolean }>): boolean {
  return useMemo(() => items.some((item) => item.isPending === true), [items]);
}
