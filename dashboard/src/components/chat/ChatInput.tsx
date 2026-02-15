import { useState } from 'react';
import { Form, Button, InputGroup } from 'react-bootstrap';
import { FiSend } from 'react-icons/fi';

interface Props {
  onSend: (text: string) => void;
  disabled?: boolean;
}

export default function ChatInput({ onSend, disabled }: Props) {
  const [text, setText] = useState('');

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (text.trim()) {
      onSend(text.trim());
      setText('');
    }
  };

  return (
    <Form onSubmit={handleSubmit}>
      <InputGroup>
        <Form.Control
          type="text"
          value={text}
          onChange={(e) => setText(e.target.value)}
          placeholder="Type a message..."
          disabled={disabled}
        />
        <Button type="submit" variant="primary" disabled={disabled || !text.trim()}>
          <FiSend />
        </Button>
      </InputGroup>
    </Form>
  );
}
