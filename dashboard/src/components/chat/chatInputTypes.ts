/**
 * Serialized image attachment payload sent from the dashboard composer to the
 * websocket transport.
 */
export interface ChatAttachmentPayload {
  type: 'image';
  name: string;
  mimeType: string;
  dataBase64: string;
}

/**
 * Client-side attachment draft with preview metadata used before the message is sent.
 */
export interface ChatAttachmentDraft extends ChatAttachmentPayload {
  id: string;
  previewUrl: string;
}

/**
 * Outbound chat message payload emitted by the dashboard transport.
 */
export interface OutboundChatPayload {
  text: string;
  attachments: ChatAttachmentPayload[];
  memoryPreset?: string | null;
}

/**
 * Slash-command suggestion displayed in the chat composer autocomplete UI.
 */
export interface CommandSuggestion {
  key: string;
  label: string;
  description: string;
  insertText: string;
}

/**
 * Minimal slash-command definition used by parser and suggestion helpers.
 */
export interface CommandDefinition {
  name: string;
  description: string;
}

/**
 * Parsed slash-command input state used by the composer suggestion system.
 */
export interface ParsedCommandInput {
  tokens: string[];
  activeTokenIndex: number;
  activeQuery: string;
}
