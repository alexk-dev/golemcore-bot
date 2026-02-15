interface Props {
  role: string;
  content: string;
}

export default function MessageBubble({ role, content }: Props) {
  return (
    <div className={`d-flex ${role === 'user' ? 'justify-content-end' : 'justify-content-start'}`}>
      <div className={`message-bubble ${role}`}>
        <div style={{ whiteSpace: 'pre-wrap' }}>{content}</div>
      </div>
    </div>
  );
}
