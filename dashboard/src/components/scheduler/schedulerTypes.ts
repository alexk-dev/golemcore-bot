export type SchedulerTargetType = 'GOAL' | 'TASK';
export type SchedulerFrequency = 'daily' | 'weekdays' | 'weekly' | 'custom';

export interface ScheduleFormState {
  targetType: SchedulerTargetType;
  targetId: string;
  frequency: SchedulerFrequency;
  days: number[];
  time: string;
  limitInput: string;
}
