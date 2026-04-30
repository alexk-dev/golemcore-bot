import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import ToolCallTimeline from './ToolCallTimeline';
import type { ToolCallViewModel } from './types';

const calls: ToolCallViewModel[] = [
  {
    id: 't1',
    runId: 'r',
    toolName: 'read_file',
    displayTarget: 'optimizer_config.yaml',
    status: 'success',
    durationMs: 180,
  },
  {
    id: 't2',
    runId: 'r',
    toolName: 'run_command',
    displayTarget: 'python optimizer.py --config optimizer_config.yaml',
    status: 'failed',
    durationMs: 12_400,
    errorCode: 'subprocess.failed',
    errorMessage: 'optimizer crashed',
  },
  {
    id: 't3',
    runId: 'r',
    toolName: 'write_file',
    displayTarget: 'optimizer_report.md',
    status: 'skipped',
  },
];

describe('ToolCallTimeline', () => {
  it('renders the call count and tool names', () => {
    const html = renderToStaticMarkup(<ToolCallTimeline calls={calls} />);
    expect(html).toContain('Tool calls');
    expect(html).toContain('read_file');
    expect(html).toContain('run_command');
    expect(html).toContain('write_file');
  });

  it('renders durations and status labels', () => {
    const html = renderToStaticMarkup(<ToolCallTimeline calls={calls} />);
    expect(html).toContain('180ms');
    expect(html).toContain('12.4s');
    expect(html).toContain('Success');
    expect(html).toContain('Failed');
    expect(html).toContain('Skipped');
  });
});
