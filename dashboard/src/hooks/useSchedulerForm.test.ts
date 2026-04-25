import { describe, expect, it } from 'vitest';
import type { SchedulerSchedule } from '../api/scheduler';
import type { ScheduleFormState } from '../components/scheduler/schedulerTypes';
import { createUpdateRequest, parseScheduleToFormState } from './useSchedulerForm';

describe('useSchedulerForm helpers', () => {
  it('parses stored webhook bearer token back into form state', () => {
    const schedule: SchedulerSchedule = {
      id: 'sched-webhook',
      type: 'SCHEDULED_TASK',
      targetId: 'scheduled-task-1',
      targetLabel: 'Scheduled task',
      cronExpression: '0 0 9 * * *',
      enabled: true,
      clearContextBeforeRun: false,
      report: {
        channelType: 'webhook',
        chatId: null,
        webhookUrl: 'https://example.com/hook',
        webhookBearerToken: 'bearer-token',
      },
      maxExecutions: -1,
      executionCount: 0,
      createdAt: null,
      updatedAt: null,
      lastExecutedAt: null,
      nextExecutionAt: null,
    };

    const form = parseScheduleToFormState(schedule);

    expect(form.reportChannelType).toBe('webhook');
    expect(form.reportWebhookUrl).toBe('https://example.com/hook');
    expect(form.reportWebhookSecret).toBe('bearer-token');
  });

  it('marks blank report channel as an explicit clear on update requests', () => {
    const form: ScheduleFormState = {
      targetType: 'SCHEDULED_TASK',
      targetId: 'scheduled-task-1',
      mode: 'simple',
      frequency: 'daily',
      days: [1],
      time: '09:00',
      cronExpression: '* * * * *',
      limitInput: '0',
      enabled: true,
      clearContextBeforeRun: false,
      reportChannelType: '',
      reportChatId: '',
      reportWebhookUrl: '',
      reportWebhookSecret: '',
    };

    const request = createUpdateRequest(form, 'scheduled-task-1', -1);

    expect(request.report?.operation).toBe('CLEAR');
    expect(request.report?.config).toBeNull();
  });
});
