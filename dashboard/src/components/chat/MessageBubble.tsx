import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { useId, useState } from 'react';
import { FiArchive, FiCpu, FiFile, FiFileText, FiUser } from 'react-icons/fi';
import { fetchProtectedFileObjectUrl } from '../../api/files';
import { getModelTierMeta } from '../../lib/modelTiers';
import type { ChatMessageAttachment } from './chatRuntimeTypes';

interface Props {
  role: string;
  content: string | null | undefined;
  model?: string | null;
  tier?: string | null;
  skill?: string | null;
  reasoning?: string | null;
  attachments?: ChatMessageAttachment[];
  modelLabel?: string;
  modelTitle?: string;
  clientStatus?: 'pending' | 'failed';
  onRetry?: () => void;
}

interface UserDeliveryStateProps {
  clientStatus?: 'pending' | 'failed';
  onRetry?: () => void;
}

interface AssistantMessageProps {
  content: string;
  model?: string | null;
  tier?: string | null;
  skill?: string | null;
  attachments: ChatMessageAttachment[];
  modelLabel?: string;
  modelTitle?: string;
}

interface AssistantBadgesProps {
  tier?: string | null;
  skill?: string | null;
}

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

function hasContent(value: string | null | undefined): boolean {
  return value != null && value.trim().length > 0;
}

function resolveModelLabel(model: string | null | undefined, modelLabel: string | undefined): string {
  if (hasContent(model)) {
    return modelLabel ?? model ?? 'System reply';
  }

  return 'System reply';
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

function AssistantBadges({ tier, skill }: AssistantBadgesProps) {
  const tierMeta = getModelTierMeta(tier);
  const hasKnownTier = tierMeta != null || hasContent(tier);
  const hasSkill = hasContent(skill);

  if (!hasSkill && !hasKnownTier) {
    return null;
  }

  return (
    <div className="d-flex align-items-center gap-2 flex-wrap justify-content-end">
      {hasSkill && (
        <span className="badge text-bg-secondary" title={skill ?? undefined}>
          {skill}
        </span>
      )}
      {hasKnownTier && (
        <span className={`badge assistant-tier-chip ${tierMeta?.badgeClassName ?? 'text-bg-secondary'}`}>
          {tierMeta?.label ?? tier}
        </span>
      )}
    </div>
  );
}

function buildThumbnailSrc(attachment: ChatMessageAttachment): string | null {
  if (attachment.thumbnailBase64 == null || attachment.thumbnailBase64.length === 0) {
    return null;
  }
  return `data:image/png;base64,${attachment.thumbnailBase64}`;
}

function resolveAttachmentIcon(mimeType: string | null | undefined) {
  const normalizedMime = (mimeType ?? '').toLowerCase();
  if (normalizedMime === 'application/pdf' || normalizedMime.startsWith('text/')) {
    return <FiFileText size={16} />;
  }
  if (normalizedMime.includes('zip') || normalizedMime.includes('tar') || normalizedMime.includes('gzip')) {
    return <FiArchive size={16} />;
  }
  return <FiFile size={16} />;
}

async function openProtectedAttachment(attachment: ChatMessageAttachment): Promise<void> {
  if (attachment.internalFilePath == null || attachment.internalFilePath.length === 0) {
    return;
  }

  const { objectUrl, revoke } = await fetchProtectedFileObjectUrl(attachment.internalFilePath);
  const isPreviewable = (attachment.mimeType ?? '').startsWith('image/') || attachment.mimeType === 'application/pdf';

  try {
    if (isPreviewable) {
      const opened = window.open(objectUrl, '_blank', 'noopener,noreferrer');
      if (opened != null) {
        return;
      }
    }

    const link = document.createElement('a');
    link.href = objectUrl;
    link.download = attachment.name ?? 'download';
    link.rel = 'noreferrer';
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
  } finally {
    window.setTimeout(() => revoke(), 60_000);
  }
}

function MessageAttachments({ attachments }: { attachments: ChatMessageAttachment[] }) {
  if (attachments.length === 0) {
    return null;
  }

  return (
    <div className="chat-attachments-row chat-attachments-row--message">
      {attachments.map((attachment, index) => {
        const label = attachment.name ?? `Attachment ${index + 1}`;
        const thumbnailSrc = attachment.type === 'image' ? buildThumbnailSrc(attachment) : null;

        if (thumbnailSrc != null) {
          return (
            <button
              key={`${attachment.internalFilePath ?? label}:${index}`}
              type="button"
              className="chat-attachment-link chat-attachment-button"
              title={label}
              onClick={() => { void openProtectedAttachment(attachment); }}
            >
              <img src={thumbnailSrc} alt={label} className="chat-attachment-thumb" />
              <span className="chat-attachment-name">{label}</span>
            </button>
          );
        }

        return (
          <button
            key={`${attachment.internalFilePath ?? label}:${index}`}
            type="button"
            className="chat-attachment-chip chat-attachment-link chat-attachment-button"
            title={label}
            onClick={() => { void openProtectedAttachment(attachment); }}
          >
            <span className="chat-attachment-icon" aria-hidden="true">
              {resolveAttachmentIcon(attachment.mimeType)}
            </span>
            <span className="chat-attachment-name">{label}</span>
          </button>
        );
      })}
    </div>
  );
}

function AssistantMessageBubble({ content, model, tier, skill, attachments, modelLabel, modelTitle }: AssistantMessageProps) {
  const resolvedModelLabel = resolveModelLabel(model, modelLabel);
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
                {resolvedModelLabel}
              </small>
            </div>
          </div>
          <AssistantBadges tier={tier} skill={skill} />
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
        <MessageAttachments attachments={attachments} />
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

export default function MessageBubble({
  role,
  content,
  model,
  tier,
  skill,
  attachments = [],
  modelLabel,
  modelTitle,
  clientStatus,
  onRetry,
}: Props) {
  const safeContent = content ?? '';
  if (role === 'assistant') {
    return (
      <AssistantMessageBubble
        content={safeContent}
        model={model}
        tier={tier}
        skill={skill}
        attachments={attachments}
        modelLabel={modelLabel}
        modelTitle={modelTitle}
      />
    );
  }

  return <UserMessageBubble content={safeContent} clientStatus={clientStatus} onRetry={onRetry} />;
}
