export interface ChatAttachmentPayload {
  type: 'image';
  name: string;
  mimeType: string;
  dataBase64: string;
}

export interface ChatAttachmentDraft extends ChatAttachmentPayload {
  id: string;
  previewUrl: string;
}

export interface OutboundChatPayload {
  text: string;
  attachments: ChatAttachmentPayload[];
}

export interface CommandSuggestion {
  key: string;
  label: string;
  description: string;
  insertText: string;
}

export interface CommandDefinition {
  name: string;
  description: string;
}

export interface ParsedCommandInput {
  tokens: string[];
  activeTokenIndex: number;
  activeQuery: string;
}
