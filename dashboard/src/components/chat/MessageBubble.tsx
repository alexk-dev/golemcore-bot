import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { useState } from 'react';

interface Props {
  role: string;
  content: string;
}

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

export default function MessageBubble({ role, content }: Props) {
  const isAssistant = role === 'assistant';

  if (isAssistant) {
    const { text, tools } = parseToolCalls(content);

    return (
      <div className={`d-flex w-100 justify-content-start fade-in`}>
        <div className="message-bubble assistant">
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
        {content}
      </div>
    </div>
  );
}
