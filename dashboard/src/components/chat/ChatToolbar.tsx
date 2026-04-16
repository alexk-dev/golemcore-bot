import type { ReactElement } from 'react';
import { FiLayout, FiMessageSquare, FiPlus } from 'react-icons/fi';
import { getExplicitModelTierOptions } from '../../lib/modelTiers';

export interface MemoryPresetOption {
  id: string;
  label: string;
}

export interface ChatToolbarProps {
  chatSessionId: string;
  connected: boolean;
  panelOpen: boolean;
  onNewChat: () => void;
  onToggleContext: () => void;
  embedded?: boolean;
  tier?: string;
  tierForce?: boolean;
  memoryPreset?: string;
  memoryPresetOptions?: MemoryPresetOption[];
  memoryPresetsLoading?: boolean;
  inheritedMemoryPresetLabel?: string | null;
  onTierChange?: (tier: string) => void;
  onForceChange?: (force: boolean) => void;
  onMemoryPresetChange?: (preset: string) => void;
}

export function ChatToolbar({
  chatSessionId,
  connected,
  panelOpen,
  onNewChat,
  onToggleContext,
  embedded = false,
  tier,
  tierForce = false,
  memoryPreset = '',
  memoryPresetOptions = [],
  memoryPresetsLoading = false,
  inheritedMemoryPresetLabel = null,
  onTierChange,
  onForceChange,
  onMemoryPresetChange,
}: ChatToolbarProps): ReactElement {
  return (
    <div className="chat-toolbar">
      <div className="chat-toolbar-inner">
        <div className="chat-toolbar-main">
          <div className="chat-toolbar-title-group">
            <div className="chat-toolbar-title">
              <FiMessageSquare aria-hidden="true" />
              <span>Workspace Chat</span>
            </div>
            <small className="chat-toolbar-subtitle">
              Session: <span className="font-mono">{chatSessionId.slice(0, 8)}</span>
            </small>
          </div>
          <div className="chat-toolbar-status" aria-live="polite">
            <span className={`status-dot ${connected ? 'online' : 'offline'}`} aria-hidden="true" />
            <small className="text-body-secondary">{connected ? 'Connected' : 'Reconnecting...'}</small>
          </div>
        </div>
        <div className="chat-toolbar-actions">
          <button
            type="button"
            className="btn btn-sm btn-secondary chat-toolbar-btn"
            onClick={onNewChat}
            title="Start a new chat session"
            aria-label="Start a new chat session"
          >
            <span className="chat-toolbar-btn-icon" aria-hidden="true">
              <FiPlus size={14} />
            </span>
            <span>New chat</span>
          </button>
          {embedded ? (
            <EmbeddedTierControls
              tier={tier ?? 'balanced'}
              tierForce={tierForce}
              memoryPreset={memoryPreset}
              memoryPresetOptions={memoryPresetOptions}
              memoryPresetsLoading={memoryPresetsLoading}
              inheritedMemoryPresetLabel={inheritedMemoryPresetLabel}
              onTierChange={onTierChange}
              onForceChange={onForceChange}
              onMemoryPresetChange={onMemoryPresetChange}
            />
          ) : (
            <button
              type="button"
              className="btn btn-sm btn-secondary chat-toolbar-btn panel-toggle-btn"
              onClick={onToggleContext}
              title={panelOpen ? 'Hide context panel' : 'Show context panel'}
              aria-label={panelOpen ? 'Hide context panel' : 'Show context panel'}
              data-testid="chat-toolbar-context-toggle"
            >
              <span className="chat-toolbar-btn-icon" aria-hidden="true">
                <FiLayout size={14} />
              </span>
              <span>{panelOpen ? 'Hide context' : 'Show context'}</span>
            </button>
          )}
        </div>
      </div>
    </div>
  );
}

interface EmbeddedTierControlsProps {
  tier: string;
  tierForce: boolean;
  memoryPreset: string;
  memoryPresetOptions: MemoryPresetOption[];
  memoryPresetsLoading: boolean;
  inheritedMemoryPresetLabel: string | null;
  onTierChange?: (tier: string) => void;
  onForceChange?: (force: boolean) => void;
  onMemoryPresetChange?: (preset: string) => void;
}

function EmbeddedTierControls({
  tier,
  tierForce,
  memoryPreset,
  memoryPresetOptions,
  memoryPresetsLoading,
  inheritedMemoryPresetLabel,
  onTierChange,
  onForceChange,
  onMemoryPresetChange,
}: EmbeddedTierControlsProps): ReactElement {
  const hasSelectedMemoryPreset = memoryPreset.length === 0
    || memoryPresetOptions.some((option) => option.id === memoryPreset);
  const inheritedLabel = inheritedMemoryPresetLabel != null && inheritedMemoryPresetLabel.length > 0
    ? `Global memory (${inheritedMemoryPresetLabel})`
    : 'Global memory';

  return (
    <div className="chat-toolbar-tier-controls">
      <label className="chat-toolbar-tier-label">
        <span className="visually-hidden">Model tier</span>
        <select
          className="form-select form-select-sm chat-toolbar-tier-select"
          value={tier}
          onChange={(event) => onTierChange?.(event.target.value)}
          aria-label="Model tier"
          data-testid="chat-toolbar-tier-select"
        >
          {getExplicitModelTierOptions().map((option) => (
            <option key={option.value} value={option.value}>
              {option.label}
            </option>
          ))}
        </select>
      </label>
      <label className="chat-toolbar-tier-label">
        <span className="visually-hidden">Memory preset</span>
        <select
          className="form-select form-select-sm chat-toolbar-memory-select"
          value={memoryPreset}
          onChange={(event) => onMemoryPresetChange?.(event.target.value)}
          aria-label="Memory preset"
          data-testid="chat-toolbar-memory-preset-select"
          disabled={memoryPresetsLoading}
        >
          <option value="">{memoryPresetsLoading ? 'Loading memory' : inheritedLabel}</option>
          {!hasSelectedMemoryPreset && (
            <option value={memoryPreset}>{memoryPreset}</option>
          )}
          {memoryPresetOptions.map((option) => (
            <option key={option.id} value={option.id}>
              {option.label}
            </option>
          ))}
        </select>
      </label>
      <label className="chat-toolbar-tier-force form-check form-switch m-0">
        <input
          type="checkbox"
          role="switch"
          className="form-check-input"
          checked={tierForce}
          onChange={(event) => onForceChange?.(event.target.checked)}
          aria-label="Force tier"
          data-testid="chat-toolbar-tier-force"
        />
        <span className="form-check-label visually-hidden">Force tier</span>
      </label>
    </div>
  );
}
