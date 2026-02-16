import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { useState } from 'react';

interface Props {
  role: string;
  content: string | null | undefined;
  model?: string | null;
  tier?: string | null;
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
  return (
    <div className="tool-call-card">
      <div className="tool-call-header" onClick={() => setOpen(!open)}>
        <span className="tool-call-icon">{open ? '\u25BC' : '\u25B6'}</span>
        <span className="tool-call-name">{tool}</span>
      </div>
      {open && (
        <div className="tool-call-body">
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

export default function MessageBubble({ role, content, model, tier }: Props) {
  const safeContent = content ?? '';
  const isAssistant = role === 'assistant';
  const normalizedTier = (tier ?? '').toLowerCase();
  const tierMeta = TIER_META[normalizedTier] ?? null;
  const leadingCommand = parseLeadingCommand(safeContent);

  if (isAssistant) {
    const { text, tools } = parseToolCalls(safeContent);

    return (
      <div className={`d-flex w-100 justify-content-start fade-in`}>
        <div className="message-bubble assistant">
          <div className="d-flex align-items-center gap-2 mb-2">
            <span className={`badge ${tierMeta?.className ?? 'text-bg-secondary'}`}>
              {tierMeta?.label ?? (tier ?? 'Unknown tier')}
            </span>
            <small className="text-body-secondary">{model ?? 'Model unavailable'}</small>
          </div>
          {tools.map((t, i) => (
            <ToolCallCard key={i} tool={t.tool} result={t.result} />
          ))}
          {text && (
            <ReactMarkdown remarkPlugins={[remarkGfm]}>
              {text}
            </ReactMarkdown>
          )}
        </div>
      </div>
    );
  }

  return (
    <div className="d-flex w-100 justify-content-end fade-in">
      <div className="message-bubble user">
        {leadingCommand ? (
          <>
            <span className="user-command-token">{leadingCommand.command}</span>
            {leadingCommand.args && <span className="user-command-args">{leadingCommand.args}</span>}
          </>
        ) : safeContent}
      </div>
    </div>
  );
}
