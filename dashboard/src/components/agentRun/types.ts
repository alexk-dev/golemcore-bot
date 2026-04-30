/**
 * Agent run view models. These types isolate UI components from raw backend
 * payloads — adapters in this module normalize websocket events and chat
 * history into discriminated unions consumable by the harness components.
 *
 * Spec: golemcore_harness_ui_redesign_spec.md §17.
 */

export type AgentRunStatus =
  | 'idle'
  | 'running'
  | 'waiting_approval'
  | 'waiting_retry'
  | 'paused'
  | 'completed'
  | 'failed'
  | 'cancelled';

export type AgentRunMode = 'plan_on' | 'plan_off';

export interface ContextUsageViewModel {
  usedTokens: number;
  maxTokens: number;
  percentage: number;
  state: 'normal' | 'warning' | 'critical';
}

export interface ToolStatsViewModel {
  totalCalls: number;
  successful: number;
  failed: number;
  skipped: number;
}

export interface AgentRunViewModel {
  id: string;
  sessionId: string;
  title: string;
  status: AgentRunStatus;
  mode: AgentRunMode;
  modelLabel: string;
  tierLabel: string;
  startedAt: string;
  updatedAt: string;
  durationMs: number;
  stepCount: number;
  currentStepIndex?: number;
  nextStepTitle?: string;
  context?: ContextUsageViewModel;
  toolStats?: ToolStatsViewModel;
}

export type PlanStepStatus =
  | 'pending'
  | 'running'
  | 'completed'
  | 'failed'
  | 'skipped'
  | 'waiting_approval';

export interface PlanStepViewModel {
  id: string;
  index: number;
  title: string;
  description?: string;
  status: PlanStepStatus;
  startedAt?: string;
  completedAt?: string;
  relatedToolCallIds: string[];
  errorCode?: string;
}

export interface PlanViewModel {
  id: string;
  runId: string;
  version: number;
  updatedAt: string;
  steps: PlanStepViewModel[];
}

export type ToolCallStatus =
  | 'pending'
  | 'running'
  | 'success'
  | 'failed'
  | 'skipped'
  | 'cancelled';

export interface ToolCallViewModel {
  id: string;
  runId: string;
  planStepId?: string;
  toolName: string;
  displayTarget?: string;
  status: ToolCallStatus;
  startedAt?: string;
  completedAt?: string;
  durationMs?: number;
  inputPreview?: string;
  outputPreview?: string;
  errorCode?: string;
  errorMessage?: string;
  retryCount?: number;
}

export type IncidentSeverity = 'info' | 'warning' | 'error' | 'critical';

export type IncidentActionId =
  | 'retry_now'
  | 'switch_model'
  | 'continue_manually'
  | 'open_logs'
  | 'cancel_retry'
  | 'export_debug_bundle'
  | 'pause_task'
  | 'report_issue';

export type IncidentActionKind = 'primary' | 'secondary' | 'danger';

export interface IncidentActionViewModel {
  id: IncidentActionId;
  label: string;
  kind: IncidentActionKind;
}

export interface IncidentViewModel {
  id: string;
  runId: string;
  severity: IncidentSeverity;
  title: string;
  message: string;
  code?: string;
  createdAt: string;
  retryAt?: string;
  retryCountdownSeconds?: number;
  taskSaved: boolean;
  actions: IncidentActionViewModel[];
}

export interface UserMessageViewModel {
  id: string;
  text: string;
  createdAt: string;
}

export interface AssistantMessageViewModel {
  id: string;
  text: string;
  createdAt: string;
  modelLabel?: string;
  tierLabel?: string;
}

export interface ArtifactViewModel {
  id: string;
  name: string;
  description?: string;
  href?: string;
  mimeType?: string;
}

export interface ApprovalRequestViewModel {
  id: string;
  title: string;
  description: string;
  prompt: string;
  createdAt: string;
}

export type AgentThreadItem =
  | { type: 'user_message'; message: UserMessageViewModel }
  | { type: 'assistant_message'; message: AssistantMessageViewModel }
  | { type: 'plan'; plan: PlanViewModel }
  | { type: 'tool_calls'; calls: ToolCallViewModel[] }
  | { type: 'incident'; incident: IncidentViewModel }
  | { type: 'artifact'; artifact: ArtifactViewModel }
  | { type: 'approval_request'; approval: ApprovalRequestViewModel };
