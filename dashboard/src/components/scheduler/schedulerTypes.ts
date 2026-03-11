export type SchedulerTargetType = 'GOAL' | 'TASK';
export type SchedulerFrequency = 'daily' | 'weekdays' | 'weekly' | 'custom';
export type SchedulerMode = 'simple' | 'advanced';

export interface ScheduleFormState {
  targetType: SchedulerTargetType;
  targetId: string;
  mode: SchedulerMode;
  frequency: SchedulerFrequency;
  days: number[];
  time: string;
  cronExpression: string;
  limitInput: string;
}
