import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import type { SchedulerScheduledTask } from '../../api/scheduler';
import { ScheduledTaskEditorCard } from './ScheduledTaskEditorCard';

describe('ScheduledTaskEditorCard', () => {
  it('renders agent prompt fields for new scheduled tasks', () => {
    const html = renderToStaticMarkup(
      <ScheduledTaskEditorCard
        featureEnabled
        busy={false}
        task={null}
        onCreate={vi.fn()}
        onUpdate={vi.fn()}
        onCancelEdit={vi.fn()}
      />,
    );

    expect(html).toContain('Execution mode');
    expect(html).toContain('Agent prompt');
    expect(html).toContain('Shell command');
    expect(html).toContain('Prompt');
    expect(html).toContain('Reflection tier');
  });

  it('renders shell command fields when editing a shell scheduled task', () => {
    const task: SchedulerScheduledTask = {
      id: 'scheduled-task-1',
      title: 'Nightly cleanup',
      description: null,
      prompt: null,
      executionMode: 'SHELL_COMMAND',
      shellCommand: 'npm run cleanup',
      shellWorkingDirectory: '/srv/app',
      reflectionModelTier: null,
      reflectionTierPriority: false,
      legacySourceType: null,
      legacySourceId: null,
    };

    const html = renderToStaticMarkup(
      <ScheduledTaskEditorCard
        featureEnabled
        busy={false}
        task={task}
        onCreate={vi.fn()}
        onUpdate={vi.fn()}
        onCancelEdit={vi.fn()}
      />,
    );

    expect(html).toContain('Command');
    expect(html).toContain('Working directory');
    expect(html).toContain('Optional. Relative to the shell workspace; empty uses the shell workspace root.');
    expect(html).not.toContain('/workspace/project');
    expect(html).not.toContain('Reflection tier');
  });
});
