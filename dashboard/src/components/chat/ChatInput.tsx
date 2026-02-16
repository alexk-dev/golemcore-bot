import { useEffect, useMemo, useRef, useState } from 'react';
import { Alert, Badge, Button, Form, InputGroup } from 'react-bootstrap';
import { FiImage, FiMic, FiSend, FiX, FiRefreshCw } from 'react-icons/fi';
import { useCommands } from '../../hooks/useCommands';
import CommandAutocomplete, { filterCommands } from './CommandAutocomplete';
import { uploadImages, ImageUploadResponse } from '../../api/uploads';
import { transcribeVoice } from '../../api/voice';

interface Props {
  onSend: (text: string) => void;
  disabled?: boolean;
}

type NoticeLevel = 'advisory' | 'blocking' | 'recoverable';
type AttachmentStatus = 'uploading' | 'uploaded' | 'failed';

interface ComposerNotice {
  id: string;
  level: NoticeLevel;
  message: string;
  actionHint?: string;
}

interface AttachmentItem {
  clientId: string;
  file?: File;
  previewUrl?: string;
  server?: ImageUploadResponse;
  status: AttachmentStatus;
  error?: string;
}

const MAX_ATTACHMENTS = 5;
const MAX_IMAGE_SIZE_BYTES = 10 * 1024 * 1024;
const MAX_VOICE_BYTES = 25 * 1024 * 1024;
const COMMAND_LISTBOX_ID = 'chat-command-listbox';

function parseRequiredArgsFromUsage(usage: string): string[] {
  const required = usage.match(/<[^>]+>/g) ?? [];
  return required.map((x) => x.replace(/[<>]/g, '').trim()).filter(Boolean);
}

function getMissingRequiredArgs(input: string, usage?: string): string[] {
  if (!input.startsWith('/') || !usage) return [];
  const requiredArgs = parseRequiredArgsFromUsage(usage);
  if (requiredArgs.length === 0) return [];

  const tokens = input.trim().split(/\s+/);
  const providedArgs = Math.max(0, tokens.length - 1);
  return requiredArgs.slice(providedArgs);
}

function formatApiError(error: unknown, fallback: string): string {
  const e = error as { response?: { data?: { message?: string; error?: string } } };
  const backendMessage = e?.response?.data?.message || e?.response?.data?.error;
  return backendMessage ? `${fallback}: ${backendMessage}` : fallback;
}

function classifyErrorLevel(errorText: string): NoticeLevel {
  const normalized = errorText.toLowerCase();
  if (
    normalized.includes('failed')
    || normalized.includes('cannot')
    || normalized.includes('denied')
    || normalized.includes('please')
  ) {
    return 'recoverable';
  }
  return 'blocking';
}

function generateClientAttachmentId() {
  return `att-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
}

function formatDuration(totalSeconds: number): string {
  const m = Math.floor(totalSeconds / 60);
  const s = totalSeconds % 60;
  return `${m}:${s.toString().padStart(2, '0')}`;
}

export default function ChatInput({ onSend, disabled }: Props) {
  const [text, setText] = useState('');
  const [selectedCmdIndex, setSelectedCmdIndex] = useState(0);
  const [attachments, setAttachments] = useState<AttachmentItem[]>([]);
  const [uploading, setUploading] = useState(false);
  const [recording, setRecording] = useState(false);
  const [recordingSeconds, setRecordingSeconds] = useState(0);
  const [processingVoice, setProcessingVoice] = useState(false);
  const [errorText, setErrorText] = useState<string | null>(null);
  const [dragActive, setDragActive] = useState(false);
  const [autocompleteDismissed, setAutocompleteDismissed] = useState(false);

  const inputRef = useRef<HTMLTextAreaElement>(null);
  const fileRef = useRef<HTMLInputElement>(null);
  const mediaRecorderRef = useRef<MediaRecorder | null>(null);
  const mediaChunksRef = useRef<BlobPart[]>([]);
  const discardRecordingRef = useRef(false);

  const commandsQuery = useCommands();
  const commands = commandsQuery.data ?? [];
  const filteredCommands = useMemo(() => filterCommands(commands, text).slice(0, 8), [commands, text]);
  const showAutocomplete = text.startsWith('/') && text.trim().length > 0 && !autocompleteDismissed;

  const selectedCommand = useMemo(() => {
    if (!text.startsWith('/')) return undefined;
    const commandName = text.slice(1).trim().split(/\s+/)[0]?.toLowerCase();
    if (!commandName) return undefined;
    return commands.find((c) => c.name.toLowerCase() === commandName);
  }, [commands, text]);

  const missingArgs = useMemo(
    () => getMissingRequiredArgs(text, selectedCommand?.usage),
    [selectedCommand?.usage, text]
  );

  const commandMode = text.trimStart().startsWith('/');
  const commandTokenOnly = commandMode && !text.trim().includes(' ');
  const uploadedAttachments = attachments.filter((a) => a.status === 'uploaded' && a.server);

  const statusText = useMemo(() => {
    if (uploading) return 'Uploading image...';
    if (recording) return `Recording ${formatDuration(recordingSeconds)}`;
    if (processingVoice) return 'Transcribing...';
    return '';
  }, [uploading, recording, processingVoice, recordingSeconds]);

  const activeDescendant = showAutocomplete && filteredCommands.length > 0
    ? `${COMMAND_LISTBOX_ID}-option-${selectedCmdIndex}`
    : undefined;

  const notices = useMemo<ComposerNotice[]>(() => {
    const result: ComposerNotice[] = [];

    if (missingArgs.length > 0) {
      result.push({
        id: 'missing-args',
        level: 'advisory',
        message: `Missing args: ${missingArgs.join(', ')}`,
        actionHint: 'You can still send this message, but the command may be incomplete.',
      });
    }

    if (errorText) {
      const level = classifyErrorLevel(errorText);
      result.push({
        id: 'error',
        level,
        message: errorText,
        actionHint: level === 'recoverable'
          ? 'Please adjust input and try again.'
          : 'Resolve this issue before sending.',
      });
    }

    return result;
  }, [missingArgs, errorText]);

  const advisoryNotices = notices.filter((n) => n.level === 'advisory');
  const blockingNotices = notices.filter((n) => n.level === 'blocking');
  const recoverableNotices = notices.filter((n) => n.level === 'recoverable');

  useEffect(() => {
    if (!disabled) inputRef.current?.focus();
  }, [disabled]);

  useEffect(() => {
    setSelectedCmdIndex(0);
    setAutocompleteDismissed(false);
  }, [text]);

  useEffect(() => {
    if (!recording) return undefined;
    const timer = setInterval(() => setRecordingSeconds((x) => x + 1), 1000);
    return () => clearInterval(timer);
  }, [recording]);

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    const trimmed = text.trim();
    if (!trimmed && uploadedAttachments.length === 0) return;

    const attachmentSuffix = uploadedAttachments.length
      ? `\n\n${uploadedAttachments.map((a) => `[image](${a.server!.url})`).join('\n')}`
      : '';

    onSend(`${trimmed}${attachmentSuffix}`.trim());
    setText('');
    setAttachments([]);
    setErrorText(null);
  };

  const applySelectedCommand = () => {
    if (filteredCommands.length === 0) return;
    const selected = filteredCommands[Math.max(0, Math.min(selectedCmdIndex, filteredCommands.length - 1))];
    setText(`/${selected.name} `);
  };

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (showAutocomplete && filteredCommands.length > 0) {
      if (e.key === 'ArrowDown') {
        e.preventDefault();
        setSelectedCmdIndex((i) => Math.min(i + 1, filteredCommands.length - 1));
        return;
      }
      if (e.key === 'ArrowUp') {
        e.preventDefault();
        setSelectedCmdIndex((i) => Math.max(i - 1, 0));
        return;
      }
      if (e.key === 'Tab') {
        e.preventDefault();
        applySelectedCommand();
        return;
      }
      if (e.key === 'Enter' && !e.shiftKey) {
        e.preventDefault();
        applySelectedCommand();
        return;
      }
      if (e.key === 'Escape') {
        e.preventDefault();
        setAutocompleteDismissed(true);
        return;
      }
    }

    if (e.key === 'Escape' && showAutocomplete) {
      e.preventDefault();
      setAutocompleteDismissed(true);
      return;
    }

    if (e.key === 'Enter' && !e.shiftKey) {
      if (commandTokenOnly && !selectedCommand) {
        e.preventDefault();
        return;
      }
      e.preventDefault();
      handleSubmit(e);
    }
  };

  const processFiles = async (files: FileList | null) => {
    if (!files || files.length === 0) return;

    setErrorText(null);

    const activeCount = attachments.filter((a) => a.status !== 'failed').length;
    const roomLeft = Math.max(0, MAX_ATTACHMENTS - activeCount);
    if (roomLeft === 0) {
      setErrorText(`You can attach up to ${MAX_ATTACHMENTS} images per message. Remove one and try again.`);
      return;
    }

    const images = Array.from(files)
      .filter((f) => f.type.startsWith('image/'))
      .slice(0, roomLeft);

    if (images.length === 0) {
      setErrorText('Only image files are supported for attachments. Please choose JPG, PNG, or WebP.');
      return;
    }

    const oversized = images.find((f) => f.size > MAX_IMAGE_SIZE_BYTES);
    if (oversized) {
      setErrorText(`Image "${oversized.name}" exceeds 10MB limit. Please choose a smaller file.`);
      return;
    }

    const pendingItems: AttachmentItem[] = images.map((file) => ({
      clientId: generateClientAttachmentId(),
      file,
      previewUrl: typeof URL !== 'undefined' && URL.createObjectURL ? URL.createObjectURL(file) : undefined,
      status: 'uploading',
    }));

    setAttachments((prev) => [...prev, ...pendingItems]);

    try {
      setUploading(true);
      const uploaded = await uploadImages(images);
      setAttachments((prev) => {
        const next = [...prev];
        pendingItems.forEach((item, index) => {
          const idx = next.findIndex((x) => x.clientId === item.clientId);
          if (idx >= 0) {
            next[idx] = {
              ...next[idx],
              status: 'uploaded',
              server: uploaded[index],
              error: undefined,
            };
          }
        });
        return next;
      });
    } catch (err) {
      const message = formatApiError(err, 'Image upload failed');
      setAttachments((prev) => prev.map((item) => (
        pendingItems.some((p) => p.clientId === item.clientId)
          ? { ...item, status: 'failed', error: message }
          : item
      )));
      setErrorText(`${message}. You can retry failed files.`);
    } finally {
      setUploading(false);
    }
  };

  const retryUpload = async (clientId: string) => {
    const target = attachments.find((a) => a.clientId === clientId);
    if (!target?.file) return;

    setErrorText(null);
    setAttachments((prev) => prev.map((item) => (
      item.clientId === clientId ? { ...item, status: 'uploading', error: undefined } : item
    )));

    try {
      const uploaded = await uploadImages([target.file]);
      setAttachments((prev) => prev.map((item) => (
        item.clientId === clientId
          ? { ...item, status: 'uploaded', server: uploaded[0], error: undefined }
          : item
      )));
    } catch (err) {
      const message = formatApiError(err, 'Image upload failed');
      setAttachments((prev) => prev.map((item) => (
        item.clientId === clientId
          ? { ...item, status: 'failed', error: message }
          : item
      )));
      setErrorText(`${message}. Retry or remove failed files.`);
    }
  };

  const handlePickImages = async (e: React.ChangeEvent<HTMLInputElement>) => {
    await processFiles(e.target.files);
    e.target.value = '';
  };

  const handleDrop: React.DragEventHandler<HTMLDivElement> = async (e) => {
    e.preventDefault();
    setDragActive(false);
    await processFiles(e.dataTransfer.files);
  };

  const handleDragOver: React.DragEventHandler<HTMLDivElement> = (e) => {
    e.preventDefault();
    setDragActive(true);
  };

  const handleDragLeave: React.DragEventHandler<HTMLDivElement> = () => {
    setDragActive(false);
  };

  const stopRecording = (discard = false) => {
    discardRecordingRef.current = discard;
    mediaRecorderRef.current?.stop();
    setRecording(false);
  };

  const toggleRecording = async () => {
    if (recording) {
      stopRecording(false);
      return;
    }

    setErrorText(null);

    try {
      if (!navigator.mediaDevices?.getUserMedia) {
        setErrorText('Voice input is not supported in this browser. Please use text input.');
        return;
      }

      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      const recorder = new MediaRecorder(stream);
      mediaChunksRef.current = [];
      discardRecordingRef.current = false;
      setRecordingSeconds(0);

      recorder.ondataavailable = (ev) => {
        if (ev.data.size > 0) {
          mediaChunksRef.current.push(ev.data);
        }
      };

      recorder.onstop = async () => {
        stream.getTracks().forEach((t) => t.stop());
        if (discardRecordingRef.current) {
          mediaChunksRef.current = [];
          return;
        }

        const blob = new Blob(mediaChunksRef.current, { type: recorder.mimeType || 'audio/webm' });
        if (blob.size === 0) return;

        if (blob.size > MAX_VOICE_BYTES) {
          setErrorText('Voice recording is too large. Please keep it shorter and try again.');
          return;
        }

        try {
          setProcessingVoice(true);
          const res = await transcribeVoice(blob);
          setText((prev) => (prev ? `${prev} ${res.text}` : res.text));
        } catch (err) {
          setErrorText(formatApiError(err, 'Voice transcription failed'));
        } finally {
          setProcessingVoice(false);
        }
      };

      recorder.start();
      mediaRecorderRef.current = recorder;
      setRecording(true);
    } catch (err) {
      const message = (err as DOMException)?.name === 'NotAllowedError'
        ? 'Microphone access denied. Please allow microphone permission and retry.'
        : 'Cannot start recording. Please check microphone settings and retry.';
      setErrorText(message);
    }
  };

  return (
    <div
      className={`chat-input-area ${dragActive ? 'drag-active' : ''}`}
      onDrop={handleDrop}
      onDragOver={handleDragOver}
      onDragLeave={handleDragLeave}
    >
      <div className="visually-hidden" aria-live="polite" aria-atomic="true">
        {statusText || (notices.length > 0 ? notices.map((n) => n.message).join('. ') : '')}
      </div>

      {showAutocomplete && (
        <CommandAutocomplete
          commands={commands}
          input={text}
          selectedIndex={selectedCmdIndex}
          visible={showAutocomplete}
          listboxId={COMMAND_LISTBOX_ID}
        />
      )}

      {attachments.length > 0 && (
        <div className="chat-attachments-strip" aria-label="Selected attachments">
          {attachments.map((a, idx) => (
            <div key={a.clientId} className="chat-attachment-chip">
              {a.status === 'uploaded' && a.server?.url ? (
                <img src={a.server.url} alt="attachment" />
              ) : (
                <div className="d-flex flex-column px-2 py-1" style={{ minWidth: 120 }}>
                  <small className="text-truncate" title={a.file?.name}>{a.file?.name || `Attachment ${idx + 1}`}</small>
                  <div className="d-flex align-items-center gap-1 mt-1">
                    {a.status === 'uploading' && <Badge bg="secondary">Uploading…</Badge>}
                    {a.status === 'failed' && <Badge bg="danger">Failed</Badge>}
                  </div>
                </div>
              )}

              <div className="d-flex align-items-center gap-1">
                {a.status === 'failed' && (
                  <button
                    type="button"
                    className="btn btn-sm btn-outline-secondary"
                    onClick={() => retryUpload(a.clientId)}
                    aria-label={`Retry attachment ${idx + 1}`}
                  >
                    <FiRefreshCw />
                  </button>
                )}
                <button
                  type="button"
                  className="btn btn-sm btn-light"
                  onClick={() => setAttachments((prev) => prev.filter((item) => item.clientId !== a.clientId))}
                  aria-label={`Remove attachment ${idx + 1}`}
                >
                  <FiX />
                </button>
              </div>
            </div>
          ))}
        </div>
      )}

      {commandMode && (
        <div className="mb-2 d-flex align-items-center gap-2">
          <Badge bg="primary">Command mode</Badge>
          <small className="text-muted">Press Tab/Enter to accept suggestion, Shift+Enter for newline.</small>
        </div>
      )}

      {advisoryNotices.map((notice) => (
        <div key={notice.id} className="mb-2" data-testid="composer-notice-advisory">
          <Badge bg="secondary" className="me-2">Hint</Badge>
          <small className="text-muted">{notice.message}</small>
          {notice.actionHint && (
            <small className="d-block text-muted">{notice.actionHint}</small>
          )}
        </div>
      ))}

      {recoverableNotices.map((notice) => (
        <Alert key={notice.id} variant="warning" className="chat-input-alert mb-2 py-2" role="alert" data-testid="composer-notice-recoverable">
          <div>{notice.message}</div>
          {notice.actionHint && <small className="d-block mt-1">{notice.actionHint}</small>}
        </Alert>
      ))}

      {blockingNotices.map((notice) => (
        <Alert key={notice.id} variant="danger" className="chat-input-alert mb-2 py-2" role="alert" data-testid="composer-notice-blocking">
          <div>{notice.message}</div>
          {notice.actionHint && <small className="d-block mt-1">{notice.actionHint}</small>}
        </Alert>
      ))}

      {dragActive && (
        <div className="chat-drag-overlay" role="status" aria-live="polite">
          Drop images to attach
        </div>
      )}

      <Form onSubmit={handleSubmit}>
        <InputGroup>
          <Button
            variant="outline-secondary"
            type="button"
            onClick={() => fileRef.current?.click()}
            disabled={disabled || uploading}
            aria-label="Attach images"
          >
            <FiImage size={16} />
          </Button>

          <Button
            variant={recording ? 'danger' : 'outline-secondary'}
            type="button"
            onClick={toggleRecording}
            disabled={disabled || processingVoice}
            aria-label={recording ? 'Stop voice recording' : 'Start voice recording'}
          >
            <FiMic size={16} />
          </Button>

          <Form.Control
            as="textarea"
            ref={inputRef}
            rows={1}
            value={text}
            onChange={(e) => setText(e.target.value)}
            onKeyDown={handleKeyDown}
            placeholder="Type a message... (Shift+Enter for new line)"
            disabled={disabled}
            role="combobox"
            aria-expanded={showAutocomplete && filteredCommands.length > 0}
            aria-controls={COMMAND_LISTBOX_ID}
            aria-activedescendant={activeDescendant}
            aria-autocomplete="list"
            aria-haspopup="listbox"
            style={{ resize: 'none', maxHeight: 120, overflow: 'auto' }}
          />

          <Button
            type="submit"
            variant="primary"
            disabled={disabled || (!text.trim() && uploadedAttachments.length === 0)}
            aria-label="Send message"
          >
            <FiSend size={16} />
          </Button>
        </InputGroup>

        <input ref={fileRef} type="file" accept="image/*" multiple hidden onChange={handlePickImages} />

        {(uploading || processingVoice || recording) && (
          <div className="mt-2 d-flex gap-2 align-items-center" aria-live="polite">
            {uploading && <Badge bg="secondary">Uploading image...</Badge>}
            {recording && <Badge bg="danger">Recording {formatDuration(recordingSeconds)}</Badge>}
            {processingVoice && <Badge bg="info">Transcribing...</Badge>}
            {recording && (
              <Button
                size="sm"
                variant="outline-danger"
                type="button"
                onClick={() => stopRecording(true)}
                aria-label="Cancel voice recording"
              >
                Cancel recording
              </Button>
            )}
          </div>
        )}
      </Form>
    </div>
  );
}
