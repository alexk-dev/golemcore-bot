import { useEffect, useMemo, useRef, useState } from 'react';
import { Button, Form } from 'react-bootstrap';
import { FiMic, FiPaperclip, FiSend, FiSquare, FiX } from 'react-icons/fi';
import { useAvailableModels } from '../../hooks/useModels';
import { createUuid } from '../../utils/uuid';

export interface ChatAttachmentPayload {
  type: 'image';
  name: string;
  mimeType: string;
  dataBase64: string;
}

interface ChatAttachmentDraft extends ChatAttachmentPayload {
  id: string;
  previewUrl: string;
}

interface OutboundChatPayload {
  text: string;
  attachments: ChatAttachmentPayload[];
}

interface CommandSuggestion {
  key: string;
  label: string;
  description: string;
  insertText: string;
}

interface CommandDefinition {
  name: string;
  description: string;
}

interface SpeechRecognitionAlternativeLike {
  transcript: string;
}

interface SpeechRecognitionResultLike {
  0: SpeechRecognitionAlternativeLike;
}

interface SpeechRecognitionEventLike {
  results: ArrayLike<SpeechRecognitionResultLike>;
}

interface SpeechRecognitionLike {
  continuous: boolean;
  interimResults: boolean;
  lang: string;
  onresult: ((event: SpeechRecognitionEventLike) => void) | null;
  onend: (() => void) | null;
  onerror: (() => void) | null;
  start: () => void;
  stop: () => void;
}

interface WindowWithSpeechRecognition extends Window {
  SpeechRecognition?: new () => SpeechRecognitionLike;
  webkitSpeechRecognition?: new () => SpeechRecognitionLike;
}

const CHAT_COMMANDS: CommandDefinition[] = [
  { name: 'help', description: 'Show available commands' },
  { name: 'skills', description: 'List available skills' },
  { name: 'tools', description: 'List enabled tools' },
  { name: 'status', description: 'Show session status' },
  { name: 'new', description: 'Start a new conversation' },
  { name: 'reset', description: 'Reset conversation' },
  { name: 'compact', description: 'Compact conversation history' },
  { name: 'tier', description: 'Set model tier' },
  { name: 'model', description: 'Configure per-tier models' },
  { name: 'stop', description: 'Stop current run' },
  { name: 'auto', description: 'Toggle auto mode' },
  { name: 'goals', description: 'List goals' },
  { name: 'goal', description: 'Create a goal' },
  { name: 'tasks', description: 'List tasks' },
  { name: 'diary', description: 'Show diary entries' },
  { name: 'schedule', description: 'Manage schedules' },
  { name: 'plan', description: 'Plan mode control' },
  { name: 'plans', description: 'List plans' },
];

interface ParsedCommandInput {
  tokens: string[];
  activeTokenIndex: number;
  activeQuery: string;
}

function parseCommandInput(text: string): ParsedCommandInput | null {
  const firstLine = text.split('\n')[0] ?? '';
  if (!firstLine.startsWith('/')) {
    return null;
  }

  const commandPart = firstLine.slice(1);
  const hasTrailingSpace = /\s$/.test(commandPart);
  const trimmed = commandPart.trim();
  const tokens = trimmed.length > 0 ? trimmed.split(/\s+/) : [];

  if (tokens.length === 0) {
    return {
      tokens: [],
      activeTokenIndex: 0,
      activeQuery: '',
    };
  }

  if (hasTrailingSpace) {
    return {
      tokens,
      activeTokenIndex: tokens.length,
      activeQuery: '',
    };
  }

  const activeTokenIndex = Math.max(0, tokens.length - 1);
  const activeQuery = (tokens[activeTokenIndex] ?? '').toLowerCase();
  return {
    tokens,
    activeTokenIndex,
    activeQuery,
  };
}

function withAppliedToken(currentText: string, tokens: string[], activeTokenIndex: number, value: string): string {
  const nextTokens = [...tokens];
  if (activeTokenIndex >= nextTokens.length) {
    nextTokens.push(value);
  } else {
    nextTokens[activeTokenIndex] = value;
  }
  const commandLine = `/${nextTokens.join(' ')} `;
  const newlineIndex = currentText.indexOf('\n');
  if (newlineIndex < 0) {
    return commandLine;
  }
  return `${commandLine}${currentText.slice(newlineIndex)}`;
}

function toSuggestions(options: Array<{ value: string; description: string; label?: string }>,
  parsed: ParsedCommandInput, text: string): CommandSuggestion[] {
  return options.map((option) => ({
    key: `${parsed.activeTokenIndex}:${option.value}`,
    label: option.label ?? option.value,
    description: option.description,
    insertText: withAppliedToken(text, parsed.tokens, parsed.activeTokenIndex, option.value),
  }));
}

interface Props {
  onSend: (payload: OutboundChatPayload) => void;
  disabled?: boolean;
}

function fileToDataUrl(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result ?? ''));
    reader.onerror = () => reject(new Error('Failed to read file'));
    reader.readAsDataURL(file);
  });
}

export default function ChatInput({ onSend, disabled }: Props) {
  const [text, setText] = useState('');
  const [isRecording, setIsRecording] = useState(false);
  const [isDragOver, setIsDragOver] = useState(false);
  const [attachments, setAttachments] = useState<ChatAttachmentDraft[]>([]);
  const [activeCommandIndex, setActiveCommandIndex] = useState(0);
  const { data: availableModels } = useAvailableModels();

  const inputRef = useRef<HTMLTextAreaElement>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const recognitionRef = useRef<SpeechRecognitionLike | null>(null);
  const modelIds = useMemo(() => {
    const groupedModels = availableModels ?? {};
    return Object.values(groupedModels)
      .flatMap((models) => models.map((model) => model.id))
      .sort();
  }, [availableModels]);
  const modelReasoningLevels = useMemo(() => {
    const groupedModels = availableModels ?? {};
    return Array.from(new Set(
      Object.values(groupedModels)
        .flatMap((models) => models.flatMap((model) => model.reasoningLevels ?? [])),
    )).sort();
  }, [availableModels]);
  const commandInput = useMemo(() => parseCommandInput(text), [text]);
  const commandSuggestions = useMemo(() => {
    if (commandInput == null) {
      return [] as CommandSuggestion[];
    }

    const { tokens, activeTokenIndex, activeQuery } = commandInput;
    const commandName = (tokens[0] ?? '').toLowerCase();

    if (activeTokenIndex === 0) {
      const options = CHAT_COMMANDS
        .filter((command) => command.name.startsWith(activeQuery))
        .slice(0, 8)
        .map((command) => ({ value: command.name, label: `/${command.name}`, description: command.description }));
      return toSuggestions(options, commandInput, text);
    }

    if (commandName === 'tier') {
      if (activeTokenIndex === 1) {
        return toSuggestions(
          ['balanced', 'smart', 'coding', 'deep']
            .filter((value) => value.startsWith(activeQuery))
            .map((value) => ({ value, description: 'Model tier' })),
          commandInput,
          text,
        );
      }
      if (activeTokenIndex === 2 && (tokens[1] ?? '').length > 0) {
        return toSuggestions(
          ['force']
            .filter((value) => value.startsWith(activeQuery))
            .map((value) => ({ value, description: 'Pin selected tier' })),
          commandInput,
          text,
        );
      }
    }

    if (commandName === 'plan' && activeTokenIndex === 1) {
      return toSuggestions(
        ['on', 'off', 'status', 'approve', 'cancel', 'resume']
          .filter((value) => value.startsWith(activeQuery))
          .map((value) => ({ value, description: 'Plan mode action' })),
        commandInput,
        text,
      );
    }

    if (commandName === 'compact' && activeTokenIndex === 1) {
      return toSuggestions(
        ['20', '50', '100']
          .filter((value) => value.startsWith(activeQuery))
          .map((value) => ({ value, description: 'Keep last N messages during compaction' })),
        commandInput,
        text,
      );
    }

    if (commandName === 'schedule' && activeTokenIndex === 1) {
      return toSuggestions(
        [
          { value: 'list', description: 'List active schedules' },
          { value: 'goal', description: 'Schedule a goal by id' },
          { value: 'task', description: 'Schedule a task by id' },
          { value: 'delete', description: 'Delete schedule by id' },
          { value: 'help', description: 'Show schedule command help' },
        ]
          .filter((option) => option.value.startsWith(activeQuery)),
        commandInput,
        text,
      );
    }

    if (commandName === 'auto' && activeTokenIndex === 1) {
      return toSuggestions(
        ['on', 'off']
          .filter((value) => value.startsWith(activeQuery))
          .map((value) => ({ value, description: 'Auto mode state' })),
        commandInput,
        text,
      );
    }

    if (commandName === 'diary' && activeTokenIndex === 1) {
      return toSuggestions(
        ['10', '25', '50']
          .filter((value) => value.startsWith(activeQuery))
          .map((value) => ({ value, description: 'Recent entries count' })),
        commandInput,
        text,
      );
    }

    if (commandName === 'model') {
      if (activeTokenIndex === 1) {
        return toSuggestions(
          ['list', 'balanced', 'smart', 'coding', 'deep']
            .filter((value) => value.startsWith(activeQuery))
            .map((value) => ({ value, description: value === 'list' ? 'List available models' : 'Model tier' })),
          commandInput,
          text,
        );
      }

      const selectedTier = tokens[1] ?? '';
      const isTierSelection = ['balanced', 'smart', 'coding', 'deep'].includes(selectedTier);
      if (isTierSelection && activeTokenIndex === 2) {
        const controlOptions = [
          { value: 'reasoning', description: 'Set reasoning level for tier model' },
          { value: 'reset', description: 'Reset tier override to default' },
        ];
        const modelOptions = modelIds.slice(0, 24).map((value) => ({ value, description: 'Available model' }));
        return toSuggestions(
          [...controlOptions, ...modelOptions]
            .filter((option) => option.value.startsWith(activeQuery))
            .slice(0, 10),
          commandInput,
          text,
        );
      }

      if (isTierSelection && (tokens[2] ?? '') === 'reasoning' && activeTokenIndex === 3) {
        const levels = modelReasoningLevels.length > 0
          ? modelReasoningLevels
          : ['minimal', 'low', 'medium', 'high'];
        return toSuggestions(
          levels
            .filter((value) => value.startsWith(activeQuery))
            .map((value) => ({ value, description: 'Reasoning level' })),
          commandInput,
          text,
        );
      }
    }

    return [] as CommandSuggestion[];
  }, [commandInput, modelIds, modelReasoningLevels, text]);
  const isCommandMenuOpen = disabled !== true && commandSuggestions.length > 0;

  useEffect(() => {
    if (disabled !== true) {
      inputRef.current?.focus();
    }
  }, [disabled]);

  useEffect(() => {
    return () => {
      if (recognitionRef.current != null) {
        recognitionRef.current.stop();
      }
      setAttachments((prev) => {
        prev.forEach((a) => URL.revokeObjectURL(a.previewUrl));
        return prev;
      });
    };
  }, []);

  const clearAttachments = () => {
    setAttachments((prev) => {
      prev.forEach((a) => URL.revokeObjectURL(a.previewUrl));
      return [];
    });
  };

  const addImageFiles = async (files: File[]) => {
    const imageFiles = files.filter((file) => file.type.startsWith('image/'));
    if (imageFiles.length === 0) {
      return;
    }

    const prepared: ChatAttachmentDraft[] = [];
    for (const file of imageFiles) {
      const dataUrl = await fileToDataUrl(file);
      const base64Index = dataUrl.indexOf(',');
      if (base64Index < 0) {
        continue;
      }
      const base64Data = dataUrl.slice(base64Index + 1);
      const previewUrl = URL.createObjectURL(file);
      prepared.push({
        id: createUuid(),
        type: 'image',
        name: file.name,
        mimeType: file.type.length > 0 ? file.type : 'image/png',
        dataBase64: base64Data,
        previewUrl,
      });
    }

    if (prepared.length > 0) {
      setAttachments((prev) => [...prev, ...prepared].slice(0, 6));
    }
    inputRef.current?.focus();
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
  };

  const applyCommandSuggestion = (suggestion: CommandSuggestion) => {
    setText(suggestion.insertText);
    setActiveCommandIndex(0);
    inputRef.current?.focus();
  };

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (isCommandMenuOpen && (e.key === 'ArrowDown' || e.key === 'ArrowUp')) {
      e.preventDefault();
      const direction = e.key === 'ArrowDown' ? 1 : -1;
      setActiveCommandIndex((prev) => {
        const next = prev + direction;
        if (next < 0) {
          return commandSuggestions.length - 1;
        }
        if (next >= commandSuggestions.length) {
          return 0;
        }
        return next;
      });
      return;
    }

    if (isCommandMenuOpen && (e.key === 'Tab' || (e.key === 'Enter' && !e.shiftKey))) {
      const selected = commandSuggestions[activeCommandIndex] ?? commandSuggestions[0];
      if (selected != null) {
        e.preventDefault();
        applyCommandSuggestion(selected);
        return;
      }
    }

    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleSubmit(e);
    }
  };

  const handleAttachClick = () => {
    fileInputRef.current?.click();
  };

  const handleFilesSelected = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const files = Array.from(e.target.files ?? []);
    await addImageFiles(files);
    e.target.value = '';
  };

  const handleDrop = async (e: React.DragEvent) => {
    e.preventDefault();
    setIsDragOver(false);
    const files = Array.from(e.dataTransfer.files ?? []);
    await addImageFiles(files);
  };

  const handleVoiceToggle = () => {
    if (isRecording) {
      recognitionRef.current?.stop();
      setIsRecording(false);
      return;
    }

    const speechWindow = window as WindowWithSpeechRecognition;
    const SpeechRecognitionCtor =
      speechWindow.SpeechRecognition ?? speechWindow.webkitSpeechRecognition;

    if (SpeechRecognitionCtor == null) {
      return;
    }

    const recognition = new SpeechRecognitionCtor();
    recognition.continuous = false;
    recognition.interimResults = true;
    recognition.lang = navigator.language.length > 0 ? navigator.language : 'en-US';

    recognition.onresult = (event: SpeechRecognitionEventLike) => {
      let transcript = '';
      for (let i = 0; i < event.results.length; i += 1) {
        transcript += event.results[i][0].transcript;
      }
      setText((prev) => {
        const prefix = prev.trim().length > 0 ? `${prev.trim()} ` : '';
        return `${prefix}${transcript.trim()}`;
      });
    };

    recognition.onend = () => {
      setIsRecording(false);
      recognitionRef.current = null;
    };

    recognition.onerror = () => {
      setIsRecording(false);
      recognitionRef.current = null;
    };

    recognitionRef.current = recognition;
    recognition.start();
    setIsRecording(true);
  };

  const removeAttachment = (id: string) => {
    setAttachments((prev) => {
      const target = prev.find((item) => item.id === id);
      if (target != null) {
        URL.revokeObjectURL(target.previewUrl);
      }
      return prev.filter((item) => item.id !== id);
    });
  };

  const speechSupported =
    typeof window !== 'undefined' &&
    (((window as WindowWithSpeechRecognition).SpeechRecognition != null)
      || ((window as WindowWithSpeechRecognition).webkitSpeechRecognition != null));

  return (
    <div className="chat-input-area">
      <Form onSubmit={handleSubmit}>
        <div
          className={`chat-input-shell d-flex align-items-end gap-2 ${isDragOver ? 'chat-drop-target-active' : ''}`}
          onDragOver={(e) => {
            e.preventDefault();
            if (disabled !== true) {
              setIsDragOver(true);
            }
          }}
          onDragLeave={() => setIsDragOver(false)}
          onDrop={handleDrop}
        >
          <Form.Control
            as="textarea"
            ref={inputRef}
            rows={1}
            value={text}
            onChange={(e) => {
              setText(e.target.value);
              setActiveCommandIndex(0);
            }}
            onKeyDown={handleKeyDown}
            placeholder="Type a message or drop images..."
            disabled={disabled}
            className="chat-textarea"
          />

          {isCommandMenuOpen && (
            <div className="chat-command-menu">
              {commandSuggestions.map((suggestion, index) => (
                <button
                  key={suggestion.key}
                  type="button"
                  className={`chat-command-item ${index === activeCommandIndex ? 'active' : ''}`}
                  onMouseDown={(event) => event.preventDefault()}
                  onClick={() => applyCommandSuggestion(suggestion)}
                >
                  <span className="chat-command-name">{suggestion.label}</span>
                  <span className="chat-command-desc">{suggestion.description}</span>
                </button>
              ))}
            </div>
          )}

          <div className="d-flex align-items-center gap-1 chat-inline-actions">
            <Button
              type="button"
              variant="secondary"
              disabled={disabled}
              onClick={handleAttachClick}
              className="chat-inline-icon-btn d-flex align-items-center justify-content-center"
              title="Attach images"
              aria-label="Attach images"
            >
              <FiPaperclip size={24} />
            </Button>
            <Button
              type="button"
              variant={isRecording ? 'danger' : 'secondary'}
              disabled={disabled === true || !speechSupported}
              onClick={handleVoiceToggle}
              className="chat-inline-icon-btn d-flex align-items-center justify-content-center"
              title={speechSupported ? 'Voice input' : 'Voice input is not supported in this browser'}
              aria-label="Voice input"
            >
              {isRecording ? <FiSquare size={22} /> : <FiMic size={24} />}
            </Button>
          </div>

          <Button
            type="submit"
            variant="primary"
            disabled={disabled === true || (text.trim().length === 0 && attachments.length === 0)}
            className="chat-send-btn rounded-circle d-flex align-items-center justify-content-center"
          >
            <FiSend size={18} />
          </Button>
        </div>

        {attachments.length > 0 && (
          <div className="chat-attachments-row mt-2">
            {attachments.map((attachment) => (
              <div key={attachment.id} className="chat-attachment-chip">
                <img src={attachment.previewUrl} alt={attachment.name} className="chat-attachment-thumb" />
                <span className="chat-attachment-name" title={attachment.name}>{attachment.name}</span>
                <button
                  type="button"
                  className="chat-attachment-remove"
                  onClick={() => removeAttachment(attachment.id)}
                  aria-label={`Remove ${attachment.name}`}
                >
                  <FiX size={14} />
                </button>
              </div>
            ))}
          </div>
        )}

        <input
          ref={fileInputRef}
          type="file"
          accept="image/*"
          className="d-none"
          multiple
          onChange={handleFilesSelected}
        />

        <small className="chat-input-hint text-body-secondary d-block mt-2">
          Enter to send, Shift+Enter for new line, type / for commands, drag and drop images to attach.
        </small>
      </Form>
    </div>
  );
}
