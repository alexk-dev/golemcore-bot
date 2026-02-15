import ChatWindow from '../components/chat/ChatWindow';

export default function ChatPage() {
  return (
    <div style={{ height: 'calc(100vh - 120px)' }}>
      <h4 className="mb-3">Chat</h4>
      <ChatWindow />
    </div>
  );
}
