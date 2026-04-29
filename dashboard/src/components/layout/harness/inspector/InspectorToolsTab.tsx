import { useMemo } from 'react';
import { useChatSessionStore } from '../../../../store/chatSessionStore';
import { useChatRuntimeStore } from '../../../../store/chatRuntimeStore';
import ToolCallTimeline from '../../../agentRun/ToolCallTimeline';
import IncidentCard from '../../../agentRun/IncidentCard';
import { SAMPLE_INCIDENT, SAMPLE_TOOL_CALLS } from '../../../agentRun/sampleAgentRun';
import { normalizeChatMessages } from '../../../agentRun/normalizeRunEvents';
import type { ToolCallViewModel } from '../../../agentRun/types';

function collectToolCalls(messages: ReturnType<typeof useChatRuntimeStore.getState>['sessions'][string]['messages'] | undefined, runId: string): ToolCallViewModel[] {
  if (messages == null || messages.length === 0) {
    return [];
  }
  const items = normalizeChatMessages(messages, runId);
  return items.flatMap((item) => (item.type === 'tool_calls' ? item.calls : []));
}

export default function InspectorToolsTab() {
  const sessionId = useChatSessionStore((s) => s.activeSessionId);
  const session = useChatRuntimeStore((s) => s.sessions[sessionId]);
  const liveCalls = useMemo(() => collectToolCalls(session?.messages, sessionId), [session?.messages, sessionId]);
  const calls = liveCalls.length > 0 ? liveCalls : SAMPLE_TOOL_CALLS;
  const isDemo = liveCalls.length === 0;

  return (
    <div className="harness-inspector__placeholder-stack">
      {isDemo && (
        <p className="harness-inspector__card-label">
          Demo tool history — replace with live data once the runtime emits
          structured tool events. Real tool calls extracted from chat markers
          will surface here automatically.
        </p>
      )}
      <ToolCallTimeline calls={calls} />
      {isDemo && <IncidentCard incident={SAMPLE_INCIDENT} />}
    </div>
  );
}
