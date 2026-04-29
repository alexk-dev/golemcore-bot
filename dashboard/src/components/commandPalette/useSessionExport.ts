import { useEffect } from 'react';
import toast from 'react-hot-toast';
import { useChatRuntimeStore } from '../../store/chatRuntimeStore';
import { useChatSessionStore } from '../../store/chatSessionStore';

const EXPORT_EVENT = 'harness:export-session';

function downloadJson(filename: string, payload: unknown): void {
  const blob = new Blob([JSON.stringify(payload, null, 2)], { type: 'application/json' });
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = filename;
  document.body.appendChild(link);
  link.click();
  document.body.removeChild(link);
  URL.revokeObjectURL(url);
}

export function useSessionExport(): void {
  const sessionId = useChatSessionStore((s) => s.activeSessionId);

  // Listen for the global export event so anywhere in the UI can request a download.
  useEffect(() => {
    function handle(): void {
      const session = useChatRuntimeStore.getState().sessions[sessionId];
      if (session == null || session.messages.length === 0) {
        toast('No active session to export');
        return;
      }
      const filename = `golemcore-session-${sessionId.slice(0, 8)}-${Date.now()}.json`;
      downloadJson(filename, {
        sessionId,
        exportedAt: new Date().toISOString(),
        turnMetadata: session.turnMetadata,
        messages: session.messages,
      });
      toast.success('Session exported');
    }
    window.addEventListener(EXPORT_EVENT, handle);
    return () => window.removeEventListener(EXPORT_EVENT, handle);
  }, [sessionId]);
}
