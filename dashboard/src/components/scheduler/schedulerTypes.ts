export type SchedulerTargetType = 'GOAL' | 'TASK' | 'SCHEDULED_TASK';
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
  enabled: boolean;
  clearContextBeforeRun: boolean;
  reportChannelType: string;
  reportChatId: string;
  reportWebhookUrl: string;
  reportWebhookSecret: string;
}
