import { useCallback, useEffect, useRef, useState, type ReactElement } from 'react';
import toast from 'react-hot-toast';
import { useAvailableModels } from '../../hooks/useModels';
import type { CommandSuggestion, OutboundChatPayload } from './chatInputTypes';
import { type AddImageFilesResult, useChatAttachments } from './useChatAttachments';
import { useChatCommands } from './useChatCommands';
import { useFileMentions } from './useFileMentions';
import { useSpeechRecognition } from './useSpeechRecognition';
import { useTextareaAutoResize } from './useTextareaAutoResize';
import { ChatComposerToggle } from './ChatComposerToggle';
import { ChatInputForm } from './ChatInputForm';

export type { ChatAttachmentPayload, OutboundChatPayload } from './chatInputTypes';

interface Props {
  onSend: (payload: OutboundChatPayload) => void;
  disabled?: boolean;
  running?: boolean;
  onStop?: () => void;
  composerCollapsed: boolean;
  onToggleComposerCollapsed: () => void;
}

export default function ChatInput({
  onSend,
  disabled,
  running,
  onStop,
  composerCollapsed,
  onToggleComposerCollapsed,
}: Props): ReactElement {
  const maxAttachmentBytesLabel = '8MB';
  const commandMenuId = 'chat-command-menu';
  const getCommandOptionId = (index: number): string => `${commandMenuId}-option-${index}`;
  const isDisabled = disabled === true;
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
    isDisabled,
  );
  const fileMentions = useFileMentions({
    text,
    setText,
    textareaRef: localInputRef,
  });

  // Merge refs: auto-resize ref + local ref for focus management.
  const setTextareaRef = useCallback((el: HTMLTextAreaElement | null): void => {
    (textareaRef as React.MutableRefObject<HTMLTextAreaElement | null>).current = el;
    (localInputRef as React.MutableRefObject<HTMLTextAreaElement | null>).current = el;
  }, [textareaRef]);

  // Auto-focus when enabled and visible.
  useEffect(() => {
    if (!isDisabled && !composerCollapsed) {
      localInputRef.current?.focus();
    }
  }, [composerCollapsed, isDisabled]);

  useEffect(() => {
    // Clear drag affordance if the composer is disabled or hidden.
    if (isDisabled || composerCollapsed) {
      setIsDragOver(false);
    }
  }, [composerCollapsed, isDisabled]);

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

  const handleChange = (e: React.ChangeEvent<HTMLTextAreaElement>): void => {
    setText(e.target.value);
    setActiveIndex(0);
    if (!hasInteracted) {
      setHasInteracted(true);
    }
    adjustHeight();
  };

  const handleSubmit = (e: React.FormEvent): void => {
    e.preventDefault();
    if (isDisabled) {
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

  const handleCommandSelect = (suggestion: CommandSuggestion): void => {
    const newText = applySuggestion(suggestion);
    setText(newText);
    localInputRef.current?.focus();
  };

  const handleKeyDown = (e: React.KeyboardEvent): void => {
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

  const handleFilesSelected = async (e: React.ChangeEvent<HTMLInputElement>): Promise<void> => {
    if (isDisabled) {
      e.target.value = '';
      return;
    }
    const files = Array.from(e.target.files ?? []);
    const result = await addImageFiles(files);
    notifyAttachmentResult(result);
    e.target.value = '';
    localInputRef.current?.focus();
  };

  const handleDrop = async (e: React.DragEvent): Promise<void> => {
    e.preventDefault();
    setIsDragOver(false);
    if (isDisabled) {
      return;
    }
    const result = await addImageFiles(Array.from(e.dataTransfer.files ?? []));
    notifyAttachmentResult(result);
    localInputRef.current?.focus();
  };

  const handleVoiceToggle = (): void => {
    toggleRecording((transcript) => {
      setText((prev) => {
        const prefix = prev.trim().length > 0 ? `${prev.trim()} ` : '';
        return `${prefix}${transcript}`;
      });
    });
  };

  const shellClasses = `chat-input-shell${isFocused ? ' chat-input-shell--focused' : ''}${isDragOver ? ' chat-drop-target-active' : ''}`;
  const isSubmitDisabled = isDisabled || (text.trim().length === 0 && attachments.length === 0);
  const btnClass = 'chat-send-btn d-flex align-items-center justify-content-center';
  const iconBtnClass = 'chat-inline-icon-btn d-flex align-items-center justify-content-center';
  const activeSuggestion = suggestions[activeIndex];

  return (
    <div className={`chat-input-area${composerCollapsed ? ' chat-input-area--collapsed' : ''}`}>
      <ChatComposerToggle
        composerCollapsed={composerCollapsed}
        onToggleComposerCollapsed={onToggleComposerCollapsed}
      />
      {!composerCollapsed && fileMentions.menu != null ? fileMentions.menu : null}
      {!composerCollapsed && (
        <ChatInputForm
          shellClasses={shellClasses}
          isDisabled={isDisabled}
          text={text}
          textareaRef={setTextareaRef}
          commandMenuId={commandMenuId}
          isMenuOpen={isMenuOpen}
          activeSuggestion={activeSuggestion}
          activeIndex={activeIndex}
          suggestions={suggestions}
          attachments={attachments}
          fileInputRef={fileInputRef}
          isRecording={isRecording}
          speechSupported={speechSupported}
          iconBtnClass={iconBtnClass}
          btnClass={btnClass}
          running={running}
          onStop={onStop}
          isSubmitDisabled={isSubmitDisabled}
          hasInteracted={hasInteracted}
          onSubmit={handleSubmit}
          onDragOver={(e) => { e.preventDefault(); if (!isDisabled) { setIsDragOver(true); } }}
          onDragLeave={() => setIsDragOver(false)}
          onDrop={handleDrop}
          onChange={handleChange}
          onKeyDown={handleKeyDown}
          onPaste={(e) => {
            if (!isDisabled) {
              handlePaste(e, notifyAttachmentResult);
            }
          }}
          onFocus={() => setIsFocused(true)}
          onBlur={() => setIsFocused(false)}
          onRemoveAttachment={removeAttachment}
          getCommandOptionId={getCommandOptionId}
          onCommandSelect={handleCommandSelect}
          onFilesSelected={handleFilesSelected}
          onAttachClick={() => fileInputRef.current?.click()}
          onVoiceToggle={handleVoiceToggle}
        />
      )}
    </div>
  );
}
