import { useState } from 'react';
import { FiArchive, FiDownload, FiZap } from 'react-icons/fi';
import toast from 'react-hot-toast';
import { compactSession } from '../../../../api/sessions';
import { useChatRuntimeStore } from '../../../../store/chatRuntimeStore';
import { useChatSessionStore } from '../../../../store/chatSessionStore';
import { extractErrorMessage } from '../../../../utils/extractErrorMessage';

const EXPORT_EVENT = 'harness:export-session';

export default function InspectorActionsCard() {
  const conversationKey = useChatSessionStore((s) => s.activeSessionId);
  const sessionRecordId = useChatRuntimeStore((s) => s.sessions[conversationKey]?.sessionRecordId ?? null);
  const [compacting, setCompacting] = useState(false);

  const handleCompact = async (): Promise<void> => {
    if (sessionRecordId == null) {
      toast('Compact context is available once the session has been registered with the server.');
      return;
    }
    setCompacting(true);
    try {
      const result = await compactSession(sessionRecordId);
      toast.success(`Compacted context — removed ${result.removed} message${result.removed === 1 ? '' : 's'}`);
    } catch (error: unknown) {
      toast.error(extractErrorMessage(error));
    } finally {
      setCompacting(false);
    }
  };

  const handleExportSession = (): void => {
    window.dispatchEvent(new CustomEvent(EXPORT_EVENT));
  };

  const handleDebugBundle = (): void => {
    toast('Debug bundle export will arrive once the runtime exposes a snapshot endpoint.');
  };

  return (
    <section className="harness-inspector__card" aria-label="Run actions">
      <h3 className="harness-inspector__card-title">Actions</h3>
      <div className="plan-block__actions">
        <button
          type="button"
          className="agent-btn"
          onClick={handleCompact}
          disabled={compacting}
        >
          <FiZap size={14} aria-hidden="true" />
          <span>{compacting ? 'Compacting…' : 'Compact context'}</span>
        </button>
        <button type="button" className="agent-btn" onClick={handleExportSession}>
          <FiDownload size={14} aria-hidden="true" />
          <span>Export session</span>
        </button>
        <button type="button" className="agent-btn" onClick={handleDebugBundle}>
          <FiArchive size={14} aria-hidden="true" />
          <span>Debug bundle</span>
        </button>
      </div>
    </section>
  );
}
