/**
 * Reference fixtures used by the harness demo surfaces and component tests.
 * They mirror the example agent run from the redesign spec
 * (golemcore_harness_ui_redesign_spec.md §23).
 */
import type { IncidentViewModel, PlanViewModel, ToolCallViewModel } from './types';

export const SAMPLE_PLAN: PlanViewModel = {
  id: 'sample-plan',
  runId: 'sample-run',
  version: 1,
  updatedAt: '2026-04-29T10:31:00.000Z',
  steps: [
    { id: 's1', index: 1, title: 'Inspect current optimizer config', status: 'completed', relatedToolCallIds: ['t1'] },
    { id: 's2', index: 2, title: 'Update switching logic (net uplift > 0)', status: 'completed', relatedToolCallIds: ['t2'] },
    { id: 's3', index: 3, title: 'Run optimizer backtest (30d)', status: 'running', relatedToolCallIds: ['t3'] },
    { id: 's4', index: 4, title: 'Validate results and compare', status: 'pending', relatedToolCallIds: [] },
    { id: 's5', index: 5, title: 'Save report and summary', status: 'pending', relatedToolCallIds: [] },
  ],
};

export const SAMPLE_TOOL_CALLS: ToolCallViewModel[] = [
  {
    id: 't1',
    runId: 'sample-run',
    planStepId: 's1',
    toolName: 'read_file',
    displayTarget: 'optimizer_config.yaml',
    status: 'success',
    durationMs: 180,
    inputPreview: 'path: optimizer_config.yaml',
    outputPreview: 'switching:\n  threshold: 0.0\n  source: legacy',
  },
  {
    id: 't2',
    runId: 'sample-run',
    planStepId: 's2',
    toolName: 'update_file',
    displayTarget: 'optimizer_config.yaml',
    status: 'success',
    durationMs: 240,
    inputPreview: 'apply diff: switching.threshold = 0.05',
    outputPreview: 'updated 1 file',
  },
  {
    id: 't3',
    runId: 'sample-run',
    planStepId: 's3',
    toolName: 'run_command',
    displayTarget: 'python optimizer.py --config optimizer_config.yaml',
    status: 'failed',
    durationMs: 12_400,
    errorCode: 'subprocess.failed',
    errorMessage: 'Traceback (most recent call last):\n  File "optimizer.py", line 42\n    raise RuntimeError("provider unavailable")',
    inputPreview: 'cwd: ./optimizer\ncmd: python optimizer.py --config optimizer_config.yaml',
  },
  {
    id: 't4',
    runId: 'sample-run',
    planStepId: 's4',
    toolName: 'write_file',
    displayTarget: 'optimizer_report.md',
    status: 'skipped',
  },
];

export const SAMPLE_INCIDENT: IncidentViewModel = {
  id: 'inc-1',
  runId: 'sample-run',
  severity: 'error',
  title: 'LLM provider is temporarily unavailable',
  message: 'Your task has been saved and will retry automatically in 5 minutes.',
  code: 'llm.provider.circuit.open',
  createdAt: '2026-04-29T10:43:00.000Z',
  retryCountdownSeconds: 299,
  taskSaved: true,
  actions: [
    { id: 'retry_now', label: 'Retry now', kind: 'primary' },
    { id: 'switch_model', label: 'Switch model', kind: 'secondary' },
    { id: 'continue_manually', label: 'Continue manually', kind: 'secondary' },
    { id: 'open_logs', label: 'Open logs', kind: 'secondary' },
  ],
};
