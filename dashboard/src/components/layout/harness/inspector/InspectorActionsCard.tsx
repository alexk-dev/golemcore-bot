import { FiArchive, FiDownload, FiZap } from 'react-icons/fi';
import toast from 'react-hot-toast';

interface InspectorActionsCardProps {
  onCompactContext?: () => void;
  onExportSession?: () => void;
  onDebugBundle?: () => void;
}

function announceComingSoon(action: string): void {
  toast(`${action} is coming in a follow-up release`);
}

export default function InspectorActionsCard({
  onCompactContext,
  onExportSession,
  onDebugBundle,
}: InspectorActionsCardProps) {
  const compact = onCompactContext ?? (() => announceComingSoon('Context compaction'));
  const exportSession = onExportSession ?? (() => announceComingSoon('Session export'));
  const debugBundle = onDebugBundle ?? (() => announceComingSoon('Debug bundle export'));

  return (
    <section className="harness-inspector__card" aria-label="Run actions">
      <h3 className="harness-inspector__card-title">Actions</h3>
      <div className="plan-block__actions">
        <button type="button" className="agent-btn" onClick={compact}>
          <FiZap size={14} aria-hidden="true" />
          <span>Compact context</span>
        </button>
        <button type="button" className="agent-btn" onClick={exportSession}>
          <FiDownload size={14} aria-hidden="true" />
          <span>Export session</span>
        </button>
        <button type="button" className="agent-btn" onClick={debugBundle}>
          <FiArchive size={14} aria-hidden="true" />
          <span>Debug bundle</span>
        </button>
      </div>
    </section>
  );
}
