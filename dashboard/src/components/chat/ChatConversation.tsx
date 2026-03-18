import type { ReactElement, RefObject } from 'react';
import { FiMessageSquare } from 'react-icons/fi';
import type { ModelsConfig } from '../../api/models';
import { buildModelTitle, formatModelDisplayLabel } from '../../utils/modelLabel';
import type { ChatMessage, LiveProgressUpdate } from './chatRuntimeTypes';
import MessageBubble from './MessageBubble';

const STARTER_PROMPTS = [
  {
    key: 'plan-next',
    label: 'Plan next step',
    text: 'Review the current status and suggest the next three prioritized steps.',
  },
  {
    key: 'risk-check',
    label: 'Find risks',
    text: 'Analyze the current work and list the most likely risks with mitigations.',
  },
  {
    key: 'draft-update',
    label: 'Draft update',
    text: 'Write a concise progress update I can send to the team.',
  },
];

interface ChatConversationProps {
  scrollRef: RefObject<HTMLDivElement>;
  historyLoading: boolean;
  historyError: string | null;
  isLoadingEarlier: boolean;
  hasMoreHistory: boolean;
  messages: ChatMessage[];
  typing: boolean;
  progress: LiveProgressUpdate | null;
  modelsConfig: ModelsConfig | null | undefined;
  onScroll: () => void;
  onRetryHistory: () => void;
  onLoadEarlierMessages: () => void;
  onRetryMessage: (messageId: string) => void;
  onStarterPromptSelect: (text: string) => void;
}

export function ChatConversation({
  scrollRef,
  historyLoading,
  historyError,
  isLoadingEarlier,
  hasMoreHistory,
  messages,
  typing,
  progress,
  modelsConfig,
  onScroll,
  onRetryHistory,
  onLoadEarlierMessages,
  onRetryMessage,
  onStarterPromptSelect,
}: ChatConversationProps): ReactElement {
  return (
    <div
      className="chat-window"
      ref={scrollRef}
      onScroll={onScroll}
      role="log"
      aria-relevant="additions text"
      aria-busy={typing}
    >
      <div className="chat-content-shell">
        {historyLoading && messages.length === 0 && (
          <div className="chat-history-state" role="status" aria-live="polite">
            <span className="spinner-border spinner-border-sm" aria-hidden="true" />
            <small className="text-body-secondary">Loading history...</small>
          </div>
        )}

        {!historyLoading && historyError != null && messages.length === 0 && (
          <div className="chat-history-state" role="status" aria-live="polite">
            <small className="text-danger">{historyError}</small>
            <button
              type="button"
              className="btn btn-sm btn-secondary"
              onClick={onRetryHistory}
            >
              Retry
            </button>
          </div>
        )}

        {hasMoreHistory && (
          <div className="text-center py-2">
            {isLoadingEarlier ? (
              <small className="text-body-secondary">Loading...</small>
            ) : (
              <button
                type="button"
                className="btn btn-sm btn-secondary chat-history-load-btn"
                onClick={onLoadEarlierMessages}
              >
                Load earlier messages
              </button>
            )}
          </div>
        )}

        {!historyLoading && historyError == null && messages.length === 0 && (
          <div className="chat-empty-state" role="status" aria-live="polite">
            <div className="chat-empty-state-icon" aria-hidden="true">
              <FiMessageSquare />
            </div>
            <h2 className="chat-empty-state-title">Start a focused conversation</h2>
            <p className="chat-empty-state-text">
              Pick a starter prompt or type your own message below.
            </p>
            <div className="chat-starter-grid">
              {STARTER_PROMPTS.map((prompt) => (
                <button
                  key={prompt.key}
                  type="button"
                  className="chat-starter-card"
                  onClick={() => onStarterPromptSelect(prompt.text)}
                >
                  <span className="chat-starter-title">{prompt.label}</span>
                  <span className="chat-starter-text">{prompt.text}</span>
                </button>
              ))}
            </div>
          </div>
        )}

        {messages.map((message) => (
          <MessageBubble
            key={message.id}
            role={message.role}
            content={message.content}
            model={message.model}
            tier={message.tier}
            skill={message.skill}
            reasoning={message.reasoning}
            modelLabel={formatModelDisplayLabel(message.model, message.reasoning, modelsConfig)}
            modelTitle={buildModelTitle(message.model, message.reasoning, modelsConfig)}
            clientStatus={message.role === 'user' ? message.clientStatus : undefined}
            onRetry={message.role === 'user' && message.clientStatus === 'failed' && message.outbound != null
              ? () => onRetryMessage(message.id)
              : undefined}
          />
        ))}

        {progress != null && progress.text.length > 0 && (
          <div className="alert alert-light border small py-2 px-3 mb-3" role="status" aria-live="polite">
            <div className="fw-semibold mb-1">{progress.type === 'intent' ? 'Plan' : 'Progress'}</div>
            <div>{progress.text}</div>
          </div>
        )}

        {typing && (
          <div className="typing-indicator" role="status" aria-live="polite" aria-label="Assistant is typing">
            <span className="typing-label">Thinking</span>
            <span className="typing-dots" aria-hidden="true">
              <span /><span /><span />
            </span>
          </div>
        )}
      </div>
    </div>
  );
}
