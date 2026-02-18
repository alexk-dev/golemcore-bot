import { FiX } from 'react-icons/fi';
import type { ChatAttachmentDraft } from './chatInputTypes';

interface ChatAttachmentRowProps {
  attachments: ChatAttachmentDraft[];
  onRemove: (id: string) => void;
}

export function ChatAttachmentRow({ attachments, onRemove }: ChatAttachmentRowProps) {
  if (attachments.length === 0) {
    return null;
  }

  return (
    <div className="chat-attachments-row">
      {attachments.map((attachment) => (
        <div key={attachment.id} className="chat-attachment-chip">
          <img src={attachment.previewUrl} alt={attachment.name} className="chat-attachment-thumb" />
          <span className="chat-attachment-name" title={attachment.name}>{attachment.name}</span>
          <button
            type="button"
            className="chat-attachment-remove"
            onClick={() => onRemove(attachment.id)}
            aria-label={`Remove ${attachment.name}`}
          >
            <FiX size={14} />
          </button>
        </div>
      ))}
    </div>
  );
}
