import { useCallback, useEffect, useRef, useState } from 'react';
import { Button, Form } from 'react-bootstrap';
import { FiMic, FiPaperclip, FiSend, FiSquare, FiStopCircle } from 'react-icons/fi';
import toast from 'react-hot-toast';
import { useAvailableModels } from '../../hooks/useModels';
import type { CommandSuggestion, OutboundChatPayload } from './chatInputTypes';
import { ChatAttachmentRow } from './ChatAttachmentRow';
import { ChatCommandMenu } from './ChatCommandMenu';
import { type AddImageFilesResult, useChatAttachments } from './useChatAttachments';
import { useChatCommands } from './useChatCommands';
import { useSpeechRecognition } from './useSpeechRecognition';
import { useTextareaAutoResize } from './useTextareaAutoResize';

export type { ChatAttachmentPayload, OutboundChatPayload } from './chatInputTypes';

interface Props {
  onSend: (payload: OutboundChatPayload) => void;
  disabled?: boolean;
  running?: boolean;
  onStop?: () => void;
}

export default function ChatInput({ onSend, disabled, running, onStop }: Props) {
  const maxAttachmentBytesLabel = '8MB';
  const commandMenuId = 'chat-command-menu';
  const getCommandOptionId = (index: number) => `${commandMenuId}-option-${index}`;
  const [text, setText] = useState('');
  const [isFocused, setIsFocused] = useState(false);
  const [isDragOver, setIsDragOver] = useState(false);
  const [hasInteracted, setHasInteracted] = useState(false);

  const localInputRef = useRef<HTMLTextAreaElement>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);

  const { data: availableModels } = useAvailableModels();
  const { textareaRef, adjustHeight, resetHeight } = useTextareaAutoResize();
  const { attachments, addImageFiles, removeAttachment, clearAttachments, handlePaste } = useChatAttachments();
  const { isRecording, isSupported: speechSupported, toggleRecording } = useSpeechRecognition();
  const { suggestions, isMenuOpen, activeIndex, setActiveIndex, applySuggestion } = useChatCommands(
    text,
    availableModels,
    disabled === true,
  );

  // Merge refs: auto-resize ref + local ref for focus management
  const setTextareaRef = useCallback((el: HTMLTextAreaElement | null) => {
    (textareaRef as React.MutableRefObject<HTMLTextAreaElement | null>).current = el;
    (localInputRef as React.MutableRefObject<HTMLTextAreaElement | null>).current = el;
  }, [textareaRef]);

  // Auto-focus when enabled
  useEffect(() => {
    if (disabled !== true) {
      localInputRef.current?.focus();
    }
  }, [disabled]);

  const notifyAttachmentResult = useCallback((result: AddImageFilesResult): void => {
    if (result.skippedUnsupported > 0) {
      toast.error(`Skipped ${result.skippedUnsupported} unsupported file${result.skippedUnsupported > 1 ? 's' : ''}.`);
    }
    if (result.skippedOversized > 0) {
      toast.error(`Skipped ${result.skippedOversized} image${result.skippedOversized > 1 ? 's' : ''} larger than ${maxAttachmentBytesLabel}.`);
    }
    if (result.skippedLimit > 0) {
      toast(`You can attach up to 6 images at once. Skipped ${result.skippedLimit}.`, { icon: 'i' });
    }
  }, [maxAttachmentBytesLabel]);

  const handleChange = (e: React.ChangeEvent<HTMLTextAreaElement>) => {
    setText(e.target.value);
    setActiveIndex(0);
    if (!hasInteracted) {
      setHasInteracted(true);
    }
    adjustHeight();
  };

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (disabled === true) {
      return;
    }
    const trimmed = text.trim();
    if (trimmed.length === 0 && attachments.length === 0) {
      return;
    }

    onSend({
      text: trimmed,
      attachments: attachments.map((a) => ({
        type: a.type,
        name: a.name,
        mimeType: a.mimeType,
        dataBase64: a.dataBase64,
      })),
    });

    setText('');
    clearAttachments();
    resetHeight();
  };

  const handleCommandSelect = (suggestion: CommandSuggestion) => {
    const newText = applySuggestion(suggestion);
    setText(newText);
    localInputRef.current?.focus();
  };

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (isMenuOpen && (e.key === 'ArrowDown' || e.key === 'ArrowUp')) {
      e.preventDefault();
      const direction = e.key === 'ArrowDown' ? 1 : -1;
      const next = activeIndex + direction;
      const wrapped = next < 0 ? suggestions.length - 1 : next >= suggestions.length ? 0 : next;
      setActiveIndex(wrapped);
      return;
    }
    if (isMenuOpen && (e.key === 'Tab' || (e.key === 'Enter' && !e.shiftKey))) {
      const selected = suggestions[activeIndex] ?? suggestions[0];
      if (selected !== undefined) {
        e.preventDefault();
        handleCommandSelect(selected);
        return;
      }
    }
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleSubmit(e);
    }
  };

  const handleFilesSelected = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const files = Array.from(e.target.files ?? []);
    const result = await addImageFiles(files);
    notifyAttachmentResult(result);
    e.target.value = '';
    localInputRef.current?.focus();
  };

  const handleDrop = async (e: React.DragEvent) => {
    e.preventDefault();
    setIsDragOver(false);
    const result = await addImageFiles(Array.from(e.dataTransfer.files ?? []));
    notifyAttachmentResult(result);
    localInputRef.current?.focus();
  };

  const handleVoiceToggle = () => {
    toggleRecording((transcript) => {
      setText((prev) => {
        const prefix = prev.trim().length > 0 ? `${prev.trim()} ` : '';
        return `${prefix}${transcript}`;
      });
    });
  };

  const shellClasses = `chat-input-shell${isFocused ? ' chat-input-shell--focused' : ''}${isDragOver ? ' chat-drop-target-active' : ''}`;
  const isSubmitDisabled = disabled === true || (text.trim().length === 0 && attachments.length === 0);
  const btnClass = 'chat-send-btn rounded-circle d-flex align-items-center justify-content-center';
  const iconBtnClass = 'chat-inline-icon-btn d-flex align-items-center justify-content-center';
  const activeSuggestion = suggestions[activeIndex];

  return (
    <div className="chat-input-area">
      <Form onSubmit={handleSubmit}>
        <div
          className={shellClasses}
          onDragOver={(e) => { e.preventDefault(); if (disabled !== true) { setIsDragOver(true); } }}
          onDragLeave={() => setIsDragOver(false)}
          onDrop={handleDrop}
        >
          <ChatAttachmentRow attachments={attachments} onRemove={removeAttachment} />
          <Form.Control
            as="textarea" ref={setTextareaRef} rows={1} value={text}
            onChange={handleChange} onKeyDown={handleKeyDown} onPaste={(e) => handlePaste(e, notifyAttachmentResult)}
            onFocus={() => setIsFocused(true)} onBlur={() => setIsFocused(false)}
            placeholder="Type a message or drop images..." disabled={disabled} className="chat-textarea"
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
              onSelect={handleCommandSelect}
            />
          )}
          <div className="chat-input-actions">
            <div className="chat-inline-actions">
              <Button type="button" variant="secondary" disabled={disabled}
                onClick={() => fileInputRef.current?.click()}
                className={iconBtnClass} title="Attach images" aria-label="Attach images">
                <FiPaperclip size={24} />
              </Button>
              <Button type="button" variant={isRecording ? 'danger' : 'secondary'}
                disabled={disabled === true || !speechSupported} onClick={handleVoiceToggle}
                className={iconBtnClass}
                title={speechSupported ? 'Voice input' : 'Voice input is not supported in this browser'}
                aria-label="Voice input">
                {isRecording ? <FiSquare size={22} /> : <FiMic size={24} />}
              </Button>
            </div>
            {running === true && onStop !== undefined ? (
              <Button type="button" variant="danger" onClick={onStop}
                className={btnClass} title="Stop generation" aria-label="Stop generation">
                <FiStopCircle size={20} />
              </Button>
            ) : (
              <Button type="submit" variant="primary" disabled={isSubmitDisabled} className={btnClass} aria-label="Send message" title="Send message">
                <FiSend size={18} />
              </Button>
            )}
          </div>
        </div>
        <input ref={fileInputRef} type="file" accept="image/*" className="d-none" multiple onChange={handleFilesSelected} />
        <small className={`chat-input-hint text-body-secondary d-block mt-2${hasInteracted ? ' chat-input-hint--hidden' : ''}`}>
          Enter to send, Shift+Enter for new line, type / for commands, drag and drop or paste images.
        </small>
      </Form>
    </div>
  );
}
