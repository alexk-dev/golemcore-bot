import type { ReactElement } from 'react';
import { FiCpu, FiDatabase, FiLayout, FiMessageSquare, FiPlus } from 'react-icons/fi';
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

/**
 * Toolbar shown above the workspace chat, with chat/session actions and optional
 * embedded model controls.
 */
export function ChatToolbar(props: ChatToolbarProps): ReactElement {
  const sessionShort = props.chatSessionId.slice(0, 8);
  if (props.embedded === true) {
    return (
      <EmbeddedToolbar
        connected={props.connected}
        sessionShort={sessionShort}
        onNewChat={props.onNewChat}
        tier={props.tier ?? 'balanced'}
        tierForce={props.tierForce === true}
        memoryPreset={props.memoryPreset ?? ''}
        memoryPresetOptions={props.memoryPresetOptions ?? []}
        memoryPresetsLoading={props.memoryPresetsLoading === true}
        inheritedMemoryPresetLabel={props.inheritedMemoryPresetLabel ?? null}
        onTierChange={props.onTierChange}
        onForceChange={props.onForceChange}
        onMemoryPresetChange={props.onMemoryPresetChange}
      />
    );
  }
  return (
    <StandaloneToolbar
      connected={props.connected}
      sessionShort={sessionShort}
      panelOpen={props.panelOpen}
      onNewChat={props.onNewChat}
      onToggleContext={props.onToggleContext}
    />
  );
}

interface TierControlsValues {
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

interface EmbeddedToolbarProps extends TierControlsValues {
  connected: boolean;
  sessionShort: string;
  onNewChat: () => void;
}

function EmbeddedToolbar(props: EmbeddedToolbarProps): ReactElement {
  return (
    <div className="chat-toolbar chat-toolbar--embedded">
      <div className="chat-toolbar-inner">
        <CompactStatus connected={props.connected} sessionShort={props.sessionShort} />
        <div className="chat-toolbar-actions">
          <EmbeddedTierControls
            tier={props.tier}
            tierForce={props.tierForce}
            memoryPreset={props.memoryPreset}
            memoryPresetOptions={props.memoryPresetOptions}
            memoryPresetsLoading={props.memoryPresetsLoading}
            inheritedMemoryPresetLabel={props.inheritedMemoryPresetLabel}
            onTierChange={props.onTierChange}
            onForceChange={props.onForceChange}
            onMemoryPresetChange={props.onMemoryPresetChange}
          />
          <button
            type="button"
            className="chat-toolbar-icon-btn"
            onClick={props.onNewChat}
            title="Start a new chat session"
            aria-label="Start a new chat session"
          >
            <FiPlus size={14} aria-hidden="true" />
          </button>
        </div>
      </div>
    </div>
  );
}

interface StandaloneToolbarProps {
  connected: boolean;
  sessionShort: string;
  panelOpen: boolean;
  onNewChat: () => void;
  onToggleContext: () => void;
}

function StandaloneToolbar({
  connected,
  sessionShort,
  panelOpen,
  onNewChat,
  onToggleContext,
}: StandaloneToolbarProps): ReactElement {
  return (
    <div className="chat-toolbar">
      <div className="chat-toolbar-inner">
        <StandaloneHeader connected={connected} sessionShort={sessionShort} />
        <div className="chat-toolbar-actions">
          <ContextToggleButton panelOpen={panelOpen} onToggleContext={onToggleContext} />
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
        </div>
      </div>
    </div>
  );
}

interface StatusProps {
  connected: boolean;
  sessionShort: string;
}

function CompactStatus({ connected, sessionShort }: StatusProps): ReactElement {
  const label = connected ? `Connected — session ${sessionShort}` : 'Reconnecting...';
  return (
    <div
      className="chat-toolbar-status chat-toolbar-status--compact"
      aria-live="polite"
      title={label}
    >
      <span className={`status-dot ${connected ? 'online' : 'offline'}`} aria-hidden="true" />
      <span className="sr-only">{connected ? 'Connected' : 'Reconnecting'}</span>
    </div>
  );
}

function StandaloneHeader({ connected, sessionShort }: StatusProps): ReactElement {
  return (
    <div className="chat-toolbar-main">
      <div className="chat-toolbar-title-group">
        <div className="chat-toolbar-title">
          <FiMessageSquare aria-hidden="true" />
          <span>Workspace Chat</span>
        </div>
        <small className="chat-toolbar-subtitle">
          Session: <span className="font-mono">{sessionShort}</span>
        </small>
      </div>
      <div className="chat-toolbar-status" aria-live="polite">
        <span className={`status-dot ${connected ? 'online' : 'offline'}`} aria-hidden="true" />
        <small className="text-body-secondary">{connected ? 'Connected' : 'Reconnecting...'}</small>
      </div>
    </div>
  );
}

interface ContextToggleButtonProps {
  panelOpen: boolean;
  onToggleContext: () => void;
}

function ContextToggleButton({ panelOpen, onToggleContext }: ContextToggleButtonProps): ReactElement {
  const label = panelOpen ? 'Hide context' : 'Show context';
  const title = panelOpen ? 'Hide context panel' : 'Show context panel';
  return (
    <button
      type="button"
      className="btn btn-sm btn-secondary chat-toolbar-btn panel-toggle-btn"
      onClick={onToggleContext}
      title={title}
      aria-label={title}
      data-testid="chat-toolbar-context-toggle"
    >
      <span className="chat-toolbar-btn-icon" aria-hidden="true">
        <FiLayout size={14} />
      </span>
      <span>{label}</span>
    </button>
  );
}

type EmbeddedTierControlsProps = TierControlsValues;

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
  const placeholder = memoryPresetsLoading ? 'Loading memory' : inheritedLabel;

  return (
    <div className="chat-toolbar-tier-controls">
      <label className="chat-toolbar-tier-field" title="Model tier">
        <span className="chat-toolbar-tier-field-icon" aria-hidden="true">
          <FiCpu size={12} />
        </span>
        <span className="sr-only">Model tier</span>
        <select
          className="chat-toolbar-tier-select"
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

      <label className="chat-toolbar-tier-field" title="Memory preset">
        <span className="chat-toolbar-tier-field-icon" aria-hidden="true">
          <FiDatabase size={12} />
        </span>
        <span className="sr-only">Memory preset</span>
        <select
          className="chat-toolbar-memory-select"
          value={memoryPreset}
          onChange={(event) => onMemoryPresetChange?.(event.target.value)}
          aria-label="Memory preset"
          data-testid="chat-toolbar-memory-preset-select"
          disabled={memoryPresetsLoading}
        >
          <option value="">{placeholder}</option>
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

      <label
        className={`chat-toolbar-force-toggle${tierForce ? ' is-active' : ''}`}
        title="Force the selected tier (disable auto-upgrade)"
      >
        <input
          type="checkbox"
          className="chat-toolbar-force-input"
          checked={tierForce}
          onChange={(event) => onForceChange?.(event.target.checked)}
          data-testid="chat-toolbar-tier-force"
        />
        <span className="chat-toolbar-force-label">
          Force<span className="sr-only"> tier</span>
        </span>
      </label>
    </div>
  );
}
