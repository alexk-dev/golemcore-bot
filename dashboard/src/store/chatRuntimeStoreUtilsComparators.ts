import type { ChatMessage, ChatMessageAttachment } from '../components/chat/chatRuntimeTypes';

export function areAttachmentsEqual(left: ChatMessageAttachment[], right: ChatMessageAttachment[]): boolean {
  if (left.length !== right.length) {
    return false;
  }
  return left.every((attachment, index) => {
    const other = right[index];
    return attachment.type === other.type
      && attachment.name === other.name
      && attachment.mimeType === other.mimeType
      && attachment.url === other.url
      && attachment.internalFilePath === other.internalFilePath
      && attachment.thumbnailBase64 === other.thumbnailBase64;
  });
}

export function isDuplicateAssistantDraft(message: ChatMessage, lastPersistedAssistant: ChatMessage | null): boolean {
  return message.role === 'assistant'
    && message.content === lastPersistedAssistant?.content
    && message.model === lastPersistedAssistant?.model
    && message.tier === lastPersistedAssistant?.tier
    && message.skill === lastPersistedAssistant?.skill
    && message.reasoning === lastPersistedAssistant?.reasoning
    && areAttachmentsEqual(message.attachments, lastPersistedAssistant?.attachments ?? []);
}
