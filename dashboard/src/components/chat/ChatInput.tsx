import { useState, useRef, useEffect } from 'react';
import { Button, Form } from 'react-bootstrap';
import { FiSend } from 'react-icons/fi';

interface Props {
  onSend: (text: string) => void;
  disabled?: boolean;
}

export default function ChatInput({ onSend, disabled }: Props) {
  const [text, setText] = useState('');
  const inputRef = useRef<HTMLTextAreaElement>(null);

  useEffect(() => {
    if (!disabled) inputRef.current?.focus();
  }, [disabled]);

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (text.trim()) {
      onSend(text.trim());
      setText('');
    }
  };

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleSubmit(e);
    }
  };

  return (
    <div className="chat-input-area">
      <Form onSubmit={handleSubmit}>
        <div className="d-flex align-items-end gap-2">
          <Form.Control
            as="textarea"
            ref={inputRef}
            rows={1}
            value={text}
            onChange={(e) => setText(e.target.value)}
            onKeyDown={handleKeyDown}
            placeholder="Type a message..."
            disabled={disabled}
            style={{ resize: 'none', maxHeight: 150, overflowY: 'auto', minHeight: '44px' }}
          />
          <Button 
            type="submit" 
            variant="primary" 
            disabled={disabled || !text.trim()}
            className="rounded-circle d-flex align-items-center justify-content-center"
            style={{ width: '44px', height: '44px', flexShrink: 0 }}
          >
            <FiSend size={18} />
          </Button>
        </div>
      </Form>
    </div>
  );
}
