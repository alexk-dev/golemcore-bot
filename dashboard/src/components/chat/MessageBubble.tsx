import DiffViewer from './DiffViewer';

interface ModelHint {
  id?: string;
  tier?: string;
  provider?: string;
}

interface Props {
  role: string;
  content: string;
  model?: ModelHint;
}

interface Part {
  type: 'text' | 'diff';
  value: string;
}

function parseMessageParts(content: string): Part[] {
  const parts: Part[] = [];
  const diffBlockRegex = /```diff\s*\n([\s\S]*?)```/g;

  let lastIndex = 0;
  let match: RegExpExecArray | null;

  while ((match = diffBlockRegex.exec(content)) !== null) {
    const blockStart = match.index;
    const blockEnd = diffBlockRegex.lastIndex;

    if (blockStart > lastIndex) {
      parts.push({
        type: 'text',
        value: content.slice(lastIndex, blockStart),
      });
    }

    parts.push({
      type: 'diff',
      value: match[1] ?? '',
    });

    lastIndex = blockEnd;
  }

  if (lastIndex < content.length) {
    parts.push({
      type: 'text',
      value: content.slice(lastIndex),
    });
  }

  if (parts.length === 0) {
    return [{ type: 'text', value: content }];
  }

  return parts;
}

function ModelHintBadge({ model }: { model?: ModelHint }) {
  if (!model || (!model.id && !model.tier && !model.provider)) {
    return null;
  }

  const label = [model.tier, model.id].filter(Boolean).join(' · ');
  const title = [model.provider, model.tier, model.id].filter(Boolean).join(' | ');

  return (
    <div className="message-model-hint" title={title}>
      {label || model.provider}
    </div>
  );
}

export default function MessageBubble({ role, content, model }: Props) {
  const parts = parseMessageParts(content);

  return (
    <div className={`d-flex ${role === 'user' ? 'justify-content-end' : 'justify-content-start'} fade-in`}>
      <div className={`message-bubble ${role}`}>
        {role === 'assistant' && <ModelHintBadge model={model} />}
        {parts.map((part, idx) =>
          part.type === 'diff' ? (
            <DiffViewer key={idx} diffText={part.value} />
          ) : (
            <div key={idx} style={{ whiteSpace: 'pre-wrap' }}>
              {part.value}
            </div>
          )
        )}
      </div>
    </div>
  );
}
