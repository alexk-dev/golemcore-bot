import type { ReactElement } from 'react';
import { FiChevronDown, FiChevronUp } from 'react-icons/fi';

interface ChatComposerToggleProps {
  composerCollapsed: boolean;
  onToggleComposerCollapsed: () => void;
}

export function ChatComposerToggle({
  composerCollapsed,
  onToggleComposerCollapsed,
}: ChatComposerToggleProps): ReactElement {
  const collapseLabel = composerCollapsed ? 'Show message composer' : 'Hide message composer';
  const CollapseIcon = composerCollapsed ? FiChevronUp : FiChevronDown;

  return (
    <button
      type="button"
      className="chat-composer-toggle"
      onClick={onToggleComposerCollapsed}
      aria-expanded={!composerCollapsed}
      aria-controls="chat-composer-form"
      aria-label={collapseLabel}
      title={collapseLabel}
    >
      <span className="chat-composer-toggle-grip" aria-hidden="true" />
      <span className="chat-composer-toggle-copy">{composerCollapsed ? 'Show composer' : 'Hide composer'}</span>
      <CollapseIcon size={14} aria-hidden="true" />
    </button>
  );
}
