import { Button, Form } from '../ui/tailwind-components';
import type { ClipboardEvent, DragEvent, ReactElement, RefObject } from 'react';
import { FiMic, FiPaperclip, FiSend, FiSquare, FiStopCircle } from 'react-icons/fi';
import type { ChatAttachmentDraft, CommandSuggestion } from './chatInputTypes';
import { ChatAttachmentRow } from './ChatAttachmentRow';
import { ChatCommandMenu } from './ChatCommandMenu';

interface ChatInputActionsProps {
  isDisabled: boolean;
  isRecording: boolean;
  speechSupported: boolean;
  iconBtnClass: string;
  btnClass: string;
  running?: boolean;
  onStop?: () => void;
  onAttachClick: () => void;
  onVoiceToggle: () => void;
  isSubmitDisabled: boolean;
}

export interface ChatInputFormProps {
  shellClasses: string;
  isDisabled: boolean;
  text: string;
  textareaRef: (el: HTMLTextAreaElement | null) => void;
  commandMenuId: string;
  isMenuOpen: boolean;
  activeSuggestion: CommandSuggestion | undefined;
  activeIndex: number;
  suggestions: CommandSuggestion[];
  attachments: ChatAttachmentDraft[];
  fileInputRef: RefObject<HTMLInputElement>;
  isRecording: boolean;
  speechSupported: boolean;
  iconBtnClass: string;
  btnClass: string;
  running?: boolean;
  onStop?: () => void;
  isSubmitDisabled: boolean;
  hasInteracted: boolean;
  onSubmit: (event: React.FormEvent) => void;
  onDragOver: (event: DragEvent) => void;
  onDragLeave: () => void;
  onDrop: (event: DragEvent) => void;
  onChange: (event: React.ChangeEvent<HTMLTextAreaElement>) => void;
  onKeyDown: (event: React.KeyboardEvent) => void;
  onPaste: (event: ClipboardEvent<HTMLTextAreaElement>) => void;
  onFocus: () => void;
  onBlur: () => void;
  onRemoveAttachment: (id: string) => void;
  getCommandOptionId: (index: number) => string;
  onCommandSelect: (suggestion: CommandSuggestion) => void;
  onFilesSelected: (event: React.ChangeEvent<HTMLInputElement>) => void;
  onAttachClick: () => void;
  onVoiceToggle: () => void;
}

function getVoiceButtonCopy(speechSupported: boolean, isRecording: boolean): { title: string; label: string } {
  if (!speechSupported) {
    return {
      title: 'Voice input is not supported in this browser',
      label: 'Start voice input',
    };
  }
  if (isRecording) {
    return {
      title: 'Stop voice input',
      label: 'Stop voice input',
    };
  }
  return {
    title: 'Start voice input',
    label: 'Start voice input',
  };
}

function ChatInputActions({
  isDisabled,
  isRecording,
  speechSupported,
  iconBtnClass,
  btnClass,
  running,
  onStop,
  onAttachClick,
  onVoiceToggle,
  isSubmitDisabled,
}: ChatInputActionsProps): ReactElement {
  const voiceButtonCopy = getVoiceButtonCopy(speechSupported, isRecording);
  const voiceLabel = isRecording ? 'Listening' : 'Voice';

  return (
    <div className="chat-input-actions">
      <div className="chat-inline-actions">
        <Button type="button" variant="secondary" disabled={isDisabled}
          onClick={onAttachClick}
          className={iconBtnClass} title="Attach images" aria-label="Attach images">
          <span className="chat-action-icon" aria-hidden="true">
            <FiPaperclip size={18} />
          </span>
          <span className="chat-action-label">Images</span>
        </Button>
        <Button type="button" variant={isRecording ? 'danger' : 'secondary'}
          disabled={isDisabled || !speechSupported} onClick={onVoiceToggle}
          className={iconBtnClass}
          title={voiceButtonCopy.title}
          aria-label={voiceButtonCopy.label}
          aria-pressed={isRecording}>
          <span className="chat-action-icon" aria-hidden="true">
            {isRecording ? <FiSquare size={16} /> : <FiMic size={18} />}
          </span>
          <span className="chat-action-label">{voiceLabel}</span>
        </Button>
      </div>
      {running === true && onStop !== undefined ? (
        <Button type="button" variant="danger" onClick={onStop}
          className={btnClass} title="Stop generation" aria-label="Stop generation">
          <span className="chat-action-icon" aria-hidden="true">
            <FiStopCircle size={18} />
          </span>
          <span className="chat-action-label">Stop</span>
        </Button>
      ) : (
        <Button type="submit" variant="primary" disabled={isSubmitDisabled} className={btnClass} aria-label="Send message" title="Send message">
          <span className="chat-action-icon" aria-hidden="true">
            <FiSend size={17} />
          </span>
          <span className="chat-action-label">Send</span>
        </Button>
      )}
    </div>
  );
}

export function ChatInputForm({
  shellClasses,
  isDisabled,
  text,
  textareaRef,
  commandMenuId,
  isMenuOpen,
  activeSuggestion,
  activeIndex,
  suggestions,
  attachments,
  fileInputRef,
  isRecording,
  speechSupported,
  iconBtnClass,
  btnClass,
  running,
  onStop,
  isSubmitDisabled,
  hasInteracted,
  onSubmit,
  onDragOver,
  onDragLeave,
  onDrop,
  onChange,
  onKeyDown,
  onPaste,
  onFocus,
  onBlur,
  onRemoveAttachment,
  getCommandOptionId,
  onCommandSelect,
  onFilesSelected,
  onAttachClick,
  onVoiceToggle,
}: ChatInputFormProps): ReactElement {
  return (
    <Form id="chat-composer-form" onSubmit={onSubmit} className="chat-input-form">
      <div className={shellClasses} onDragOver={onDragOver} onDragLeave={onDragLeave} onDrop={onDrop}>
        <ChatAttachmentRow attachments={attachments} onRemove={onRemoveAttachment} />
        <Form.Control
          as="textarea" ref={textareaRef} rows={1} value={text}
          onChange={onChange}
          onKeyDown={onKeyDown}
          onPaste={onPaste}
          onFocus={onFocus} onBlur={onBlur}
          placeholder="Message the assistant, type /, or drop images..." disabled={isDisabled} className="chat-textarea"
          aria-expanded={isMenuOpen}
          aria-controls={commandMenuId}
          aria-haspopup="listbox"
          aria-label="Message input"
          aria-autocomplete="list"
          aria-activedescendant={isMenuOpen && activeSuggestion != null ? getCommandOptionId(activeIndex) : undefined}
        />
        {isMenuOpen && (
          <ChatCommandMenu
            menuId={commandMenuId}
            suggestions={suggestions}
            activeIndex={activeIndex}
            getOptionId={getCommandOptionId}
            onSelect={onCommandSelect}
          />
        )}
        <ChatInputActions
          isDisabled={isDisabled}
          isRecording={isRecording}
          speechSupported={speechSupported}
          iconBtnClass={iconBtnClass}
          btnClass={btnClass}
          running={running}
          onStop={onStop}
          onAttachClick={onAttachClick}
          onVoiceToggle={onVoiceToggle}
          isSubmitDisabled={isSubmitDisabled}
        />
      </div>
      <input ref={fileInputRef} type="file" accept="image/*" className="d-none" multiple onChange={onFilesSelected} />
      <small className={`chat-input-hint text-body-secondary d-block mt-2${hasInteracted ? ' chat-input-hint--hidden' : ''}`}>
        <span className="chat-input-shortcut">Enter</span> send
        <span className="chat-input-separator">•</span>
        <span className="chat-input-shortcut">Shift + Enter</span> newline
        <span className="chat-input-separator">•</span>
        <span className="chat-input-shortcut">/</span> commands
      </small>
    </Form>
  );
}
