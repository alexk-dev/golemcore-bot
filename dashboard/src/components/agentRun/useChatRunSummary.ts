import { useEffect, useRef, useState } from 'react';
import { useChatRuntimeStore } from '../../store/chatRuntimeStore';
import { useChatSessionStore } from '../../store/chatSessionStore';
import { useRecentSessions } from '../../hooks/useSessions';
import type { AgentRunStatus } from './types';

export interface ChatRunSummary {
  title: string;
  status: AgentRunStatus;
  startedAt: string;
  durationMs: number;
  stepCount: number;
}

function resolveStatus(running: boolean, typing: boolean, hasMessages: boolean): AgentRunStatus {
  if (running) {
    return 'running';
  }
  if (typing) {
    return 'running';
  }
  if (hasMessages) {
    return 'idle';
  }
  return 'idle';
}

function resolveTitle(serverTitle: string | null | undefined, sessionId: string): string {
  if (serverTitle != null && serverTitle.trim().length > 0) {
    return serverTitle;
  }
  if (sessionId.length > 8) {
    return `Session ${sessionId.slice(0, 8)}`;
  }
  return 'Workspace chat';
}

export function useChatRunSummary(): ChatRunSummary {
  const sessionId = useChatSessionStore((s) => s.activeSessionId);
  const clientInstanceId = useChatSessionStore((s) => s.clientInstanceId);
  const session = useChatRuntimeStore((s) => s.sessions[sessionId]);
  const { data: recentSessions } = useRecentSessions('web', clientInstanceId, 5);
  const activeSummary = recentSessions?.find((entry) => entry.conversationKey === sessionId);

  const running = session?.running ?? false;
  const typing = session?.typing ?? false;
  const messages = session?.messages ?? [];
  const stepCount = messages.length;

  const startedAtRef = useRef<string>(new Date().toISOString());
  const [now, setNow] = useState<number>(() => Date.now());

  // Reset the start anchor whenever the active session changes so the duration
  // counter reflects the lifetime of the visible conversation.
  useEffect(() => {
    startedAtRef.current = new Date().toISOString();
    setNow(Date.now());
  }, [sessionId]);

  // Tick every second while there is at least one message so the duration in the
  // task header stays accurate without requiring server time.
  useEffect(() => {
    if (stepCount === 0) {
      return undefined;
    }
    const id = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(id);
  }, [stepCount]);

  const startedAtMs = new Date(startedAtRef.current).getTime();
  const durationMs = stepCount === 0 ? 0 : Math.max(0, now - startedAtMs);

  return {
    title: resolveTitle(activeSummary?.title ?? null, sessionId),
    status: resolveStatus(running, typing, stepCount > 0),
    startedAt: startedAtRef.current,
    durationMs,
    stepCount,
  };
}
