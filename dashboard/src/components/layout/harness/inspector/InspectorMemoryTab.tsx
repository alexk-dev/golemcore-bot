import { FiBookmark } from 'react-icons/fi';
import { useMemoryPresets } from '../../../../hooks/useSettings';
import type { MemoryPreset } from '../../../../api/settings';

function MemoryPresetCard({ preset }: { preset: MemoryPreset }) {
  return (
    <article className="agent-card" aria-label={`Memory preset ${preset.label}`}>
      <header className="agent-card__header">
        <FiBookmark size={14} aria-hidden="true" />
        <span>{preset.label}</span>
      </header>
      {preset.comment.length > 0 && (
        <p className="harness-inspector__card-label">{preset.comment}</p>
      )}
      <div className="harness-inspector__card-row">
        <span className="harness-inspector__card-label">Identifier</span>
        <span className="harness-inspector__card-value"><code>{preset.id}</code></span>
      </div>
    </article>
  );
}

export default function InspectorMemoryTab() {
  const { data, isLoading, isError, refetch } = useMemoryPresets();

  if (isLoading) {
    return <div className="harness-inspector__placeholder"><span>Loading memory…</span></div>;
  }
  if (isError) {
    return (
      <div className="harness-inspector__placeholder">
        <span>Failed to load memory presets</span>
        <button type="button" className="agent-btn" onClick={() => refetch()}>Retry</button>
      </div>
    );
  }

  const presets = data ?? [];

  return (
    <div className="harness-inspector__placeholder-stack">
      <p className="harness-inspector__card-label">
        Memory presets configured for the workspace. Once the runtime emits
        relevant-memory events per turn they will appear here.
      </p>
      {presets.length === 0 ? (
        <div className="harness-inspector__placeholder">
          <span>No memory presets configured.</span>
        </div>
      ) : (
        presets.map((preset) => <MemoryPresetCard key={preset.id} preset={preset} />)
      )}
    </div>
  );
}
