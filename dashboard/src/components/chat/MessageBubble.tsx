import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { useId, useState } from 'react';
import { FiCpu, FiUser } from 'react-icons/fi';

interface Props {
  role: string;
  content: string | null | undefined;
  model?: string | null;
  tier?: string | null;
  reasoning?: string | null;
  modelLabel?: string;
  modelTitle?: string;
  clientStatus?: 'pending' | 'failed';
  onRetry?: () => void;
}

interface UserDeliveryStateProps {
  clientStatus?: 'pending' | 'failed';
  onRetry?: () => void;
}

const TIER_META: Record<string, { label: string; className: string }> = {
  balanced: { label: 'Balanced', className: 'text-bg-primary' },
  smart: { label: 'Smart', className: 'text-bg-success' },
  coding: { label: 'Coding', className: 'text-bg-info' },
  deep: { label: 'Deep', className: 'text-bg-warning' },
  routing: { label: 'Routing', className: 'text-bg-dark' },
};

function ToolCallCard({ tool, result }: { tool: string; result: string }) {
  const [open, setOpen] = useState(false);
  const contentId = useId();

  return (
    <div className="tool-call-card">
      <button
        type="button"
        className="tool-call-header"
        onClick={() => setOpen(!open)}
        aria-expanded={open}
        aria-controls={contentId}
      >
        <span className="tool-call-icon">{open ? '\u25BC' : '\u25B6'}</span>
        <span className="tool-call-name">{tool}</span>
      </button>
      {open && (
        <div id={contentId} className="tool-call-body">
          <pre>{result}</pre>
        </div>
      )}
    </div>
  );
}

function parseToolCalls(content: string): { text: string; tools: { tool: string; result: string }[] } {
  const toolPattern = /\[Tool: ([^\]]+)\]\s*\[Result: ([^\]]*)\]/g;
  const tools: { tool: string; result: string }[] = [];
  let text = content;
  let match;

  while ((match = toolPattern.exec(content)) !== null) {
    tools.push({ tool: match[1], result: match[2] });
  }

  if (tools.length > 0) {
    text = content.replace(toolPattern, '').trim();
  }

  return { text, tools };
}

function parseLeadingCommand(content: string): { command: string; args: string } | null {
  const firstLine = content.split('\n')[0] ?? '';
  if (!firstLine.startsWith('/')) {
    return null;
  }

  const firstToken = firstLine.split(/\s+/)[0] ?? '';
  if (!/^\/[a-z0-9_-]+$/i.test(firstToken)) {
    return null;
  }

  const command = firstToken;
  const args = content.slice(command.length);
  return { command, args };
}

function renderUserContent(content: string, leadingCommand: { command: string; args: string } | null) {
  if (leadingCommand == null) {
    return content;
  }

  return (
    <>
      <span className="user-command-token">{leadingCommand.command}</span>
      {leadingCommand.args && <span className="user-command-args">{leadingCommand.args}</span>}
    </>
  );
}

function UserDeliveryState({ clientStatus, onRetry }: UserDeliveryStateProps) {
  if (clientStatus == null) {
    return null;
  }

  return (
    <div className="message-bubble-status" role="status" aria-live="polite">
      {clientStatus === 'pending' ? (
        <span className="text-body-secondary">Sending...</span>
      ) : (
        <>
          <span className="text-danger-emphasis">Not sent.</span>
          {onRetry !== undefined && (
            <button type="button" className="btn btn-link btn-sm p-0 message-retry-btn" onClick={onRetry}>
              Retry
            </button>
          )}
        </>
      )}
    </div>
  );
}

interface AssistantMessageProps {
  content: string;
  tier?: string | null;
  modelLabel?: string;
  modelTitle?: string;
}

function AssistantMessageBubble({ content, tier, modelLabel, modelTitle }: AssistantMessageProps) {
  const normalizedTier = (tier ?? '').toLowerCase();
  const tierMeta = TIER_META[normalizedTier] ?? null;

  const { text, tools } = parseToolCalls(content);

  return (
    <div className="message-row message-row--assistant fade-in">
      <div className="message-bubble assistant">
        <div className="assistant-message-header">
          <div className="assistant-message-meta">
            <span className="assistant-message-avatar" aria-hidden="true">
              <FiCpu size={13} />
            </span>
            <div className="assistant-message-title-group">
              <span className="assistant-message-title">Assistant</span>
              <small className="assistant-model-label" title={modelTitle}>
                {modelLabel ?? 'Model unavailable'}
              </small>
            </div>
          </div>
          <span className={`badge assistant-tier-chip ${tierMeta?.className ?? 'text-bg-secondary'}`}>
            {tierMeta?.label ?? (tier ?? 'Unknown tier')}
          </span>
        </div>
        {tools.map((tool) => (
          <ToolCallCard key={`${tool.tool}-${tool.result}`} tool={tool.tool} result={tool.result} />
        ))}
        {text && (
          <div className="assistant-message-body">
            <ReactMarkdown remarkPlugins={[remarkGfm]}>
              {text}
            </ReactMarkdown>
          </div>
        )}
      </div>
    </div>
  );
}

interface UserMessageProps {
  content: string;
  clientStatus?: 'pending' | 'failed';
  onRetry?: () => void;
}

function UserMessageBubble({ content, clientStatus, onRetry }: UserMessageProps) {
  const leadingCommand = parseLeadingCommand(content);

  return (
    <div className="message-row message-row--user fade-in">
      <div className={`message-bubble user${clientStatus != null ? ` message-bubble--${clientStatus}` : ''}`}>
        <div className="user-message-header">
          <span className="user-message-pill">
            <FiUser size={12} />
            You
          </span>
        </div>
        <div className="user-message-body">
          {renderUserContent(content, leadingCommand)}
        </div>
        <UserDeliveryState clientStatus={clientStatus} onRetry={onRetry} />
      </div>
    </div>
  );
}

export default function MessageBubble({ role, content, tier, modelLabel, modelTitle, clientStatus, onRetry }: Props) {
  const safeContent = content ?? '';
  if (role === 'assistant') {
    return (
      <AssistantMessageBubble
        content={safeContent}
        tier={tier}
        modelLabel={modelLabel}
        modelTitle={modelTitle}
      />
    );
  }

  return <UserMessageBubble content={safeContent} clientStatus={clientStatus} onRetry={onRetry} />;
}
