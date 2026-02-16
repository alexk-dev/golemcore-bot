import { useEffect, useRef, useState, useCallback } from 'react';
import { useAuthStore } from '../../store/authStore';
import MessageBubble from './MessageBubble';
import ChatInput from './ChatInput';

interface ChatMessage {
  role: string;
  content: string;
}

export default function ChatWindow() {
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [connected, setConnected] = useState(false);
  const [typing, setTyping] = useState(false);
  const wsRef = useRef<WebSocket | null>(null);
  const bottomRef = useRef<HTMLDivElement>(null);
  const token = useAuthStore((s) => s.accessToken);

  const connect = useCallback(() => {
    if (!token) return;
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const ws = new WebSocket(`${protocol}//${window.location.host}/ws/chat?token=${token}`);

    ws.onopen = () => setConnected(true);
    ws.onclose = () => setConnected(false);
    ws.onmessage = (event) => {
      try {
        const data = JSON.parse(event.data);
        if (data.type === 'system_event' && data.eventType === 'typing') {
          setTyping(true);
          setTimeout(() => setTyping(false), 3000);
          return;
        }
        if (data.type === 'assistant_chunk' || data.type === 'assistant_done') {
          setTyping(false);
          setMessages((prev) => {
            const last = prev[prev.length - 1];
            if (last && last.role === 'assistant' && data.type === 'assistant_chunk') {
              return [...prev.slice(0, -1), { role: 'assistant', content: last.content + data.text }];
            }
            return [...prev, { role: 'assistant', content: data.text }];
          });
        }
      } catch {
        // ignore parse errors
      }
    };

    wsRef.current = ws;
    return () => ws.close();
  }, [token]);

  useEffect(() => {
    const cleanup = connect();
    return cleanup;
  }, [connect]);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages, typing]);

  const handleSend = (text: string) => {
    if (wsRef.current?.readyState === WebSocket.OPEN) {
      setMessages((prev) => [...prev, { role: 'user', content: text }]);
      wsRef.current.send(JSON.stringify({ text }));
    }
  };

  return (
    <div className="d-flex flex-column h-100">
      <div className="chat-window flex-grow-1 mb-3">
        {messages.map((msg, i) => (
          <MessageBubble key={i} role={msg.role} content={msg.content} />
        ))}
        {typing && (
          <div className="text-muted small ms-2">Assistant is typing...</div>
        )}
        <div ref={bottomRef} />
      </div>
      <ChatInput onSend={handleSend} disabled={!connected} />
      {!connected && (
        <div className="text-danger small mt-1">Disconnected. Reconnecting...</div>
      )}
    </div>
  );
}
