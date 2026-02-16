import { useEffect, useMemo, useRef, useState } from 'react';
import { Alert, Badge, Button, Form, InputGroup } from 'react-bootstrap';
import { FiImage, FiMic, FiSend, FiX } from 'react-icons/fi';
import { useCommands } from '../../hooks/useCommands';
import CommandAutocomplete, { filterCommands } from './CommandAutocomplete';
import { uploadImages, ImageUploadResponse } from '../../api/uploads';
import { transcribeVoice } from '../../api/voice';

interface Props {
  onSend: (text: string) => void;
  disabled?: boolean;
}

const MAX_ATTACHMENTS = 5;
const MAX_IMAGE_SIZE_BYTES = 10 * 1024 * 1024;
const MAX_VOICE_BYTES = 25 * 1024 * 1024;

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

export default function ChatInput({ onSend, disabled }: Props) {
  const [text, setText] = useState('');
  const [selectedCmdIndex, setSelectedCmdIndex] = useState(0);
  const [attachments, setAttachments] = useState<ImageUploadResponse[]>([]);
  const [uploading, setUploading] = useState(false);
  const [recording, setRecording] = useState(false);
  const [processingVoice, setProcessingVoice] = useState(false);
  const [errorText, setErrorText] = useState<string | null>(null);

  const inputRef = useRef<HTMLTextAreaElement>(null);
  const fileRef = useRef<HTMLInputElement>(null);
  const mediaRecorderRef = useRef<MediaRecorder | null>(null);
  const mediaChunksRef = useRef<BlobPart[]>([]);

  const commandsQuery = useCommands();
  const commands = commandsQuery.data ?? [];
  const filteredCommands = useMemo(() => filterCommands(commands, text).slice(0, 8), [commands, text]);
  const showAutocomplete = text.startsWith('/') && text.trim().length > 0;

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

  useEffect(() => {
    if (!disabled) inputRef.current?.focus();
  }, [disabled]);

  useEffect(() => {
    setSelectedCmdIndex(0);
  }, [text]);

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    const trimmed = text.trim();
    if (!trimmed && attachments.length === 0) return;

    const attachmentSuffix = attachments.length
      ? `\n\n${attachments.map((a) => `[image](${a.url})`).join('\n')}`
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
    }

    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleSubmit(e);
    }
  };

  const processFiles = async (files: FileList | null) => {
    if (!files || files.length === 0) return;

    setErrorText(null);

    const roomLeft = Math.max(0, MAX_ATTACHMENTS - attachments.length);
    if (roomLeft === 0) {
      setErrorText(`You can attach up to ${MAX_ATTACHMENTS} images per message.`);
      return;
    }

    const images = Array.from(files)
      .filter((f) => f.type.startsWith('image/'))
      .slice(0, roomLeft);

    if (images.length === 0) {
      setErrorText('Only image files are supported for attachments.');
      return;
    }

    const oversized = images.find((f) => f.size > MAX_IMAGE_SIZE_BYTES);
    if (oversized) {
      setErrorText(`Image "${oversized.name}" exceeds 10MB limit.`);
      return;
    }

    try {
      setUploading(true);
      const uploaded = await uploadImages(images);
      setAttachments((prev) => [...prev, ...uploaded].slice(0, MAX_ATTACHMENTS));
    } catch (err) {
      setErrorText(formatApiError(err, 'Image upload failed'));
    } finally {
      setUploading(false);
    }
  };

  const handlePickImages = async (e: React.ChangeEvent<HTMLInputElement>) => {
    await processFiles(e.target.files);
    e.target.value = '';
  };

  const handleDrop: React.DragEventHandler<HTMLDivElement> = async (e) => {
    e.preventDefault();
    await processFiles(e.dataTransfer.files);
  };

  const handleDragOver: React.DragEventHandler<HTMLDivElement> = (e) => {
    e.preventDefault();
  };

  const toggleRecording = async () => {
    if (recording) {
      mediaRecorderRef.current?.stop();
      setRecording(false);
      return;
    }

    setErrorText(null);

    try {
      if (!navigator.mediaDevices?.getUserMedia) {
        setErrorText('Voice input is not supported in this browser.');
        return;
      }

      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      const recorder = new MediaRecorder(stream);
      mediaChunksRef.current = [];

      recorder.ondataavailable = (ev) => {
        if (ev.data.size > 0) {
          mediaChunksRef.current.push(ev.data);
        }
      };

      recorder.onstop = async () => {
        stream.getTracks().forEach((t) => t.stop());
        const blob = new Blob(mediaChunksRef.current, { type: recorder.mimeType || 'audio/webm' });
        if (blob.size === 0) return;

        if (blob.size > MAX_VOICE_BYTES) {
          setErrorText('Voice recording is too large. Please keep it shorter.');
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
        ? 'Microphone access denied. Please allow microphone permission.'
        : 'Cannot start recording. Please check microphone settings.';
      setErrorText(message);
    }
  };

  return (
    <div className="chat-input-area" onDrop={handleDrop} onDragOver={handleDragOver}>
      {showAutocomplete && (
        <CommandAutocomplete
          commands={commands}
          input={text}
          selectedIndex={selectedCmdIndex}
          visible={showAutocomplete}
        />
      )}

      {attachments.length > 0 && (
        <div className="chat-attachments-strip">
          {attachments.map((a, idx) => (
            <div key={`${a.id}-${idx}`} className="chat-attachment-chip">
              <img src={a.url} alt="attachment" />
              <button
                type="button"
                className="btn btn-sm btn-light"
                onClick={() => setAttachments((prev) => prev.filter((x) => x.id !== a.id))}
                aria-label="Remove attachment"
              >
                <FiX />
              </button>
            </div>
          ))}
        </div>
      )}

      {(missingArgs.length > 0 || errorText) && (
        <div className="mb-2">
          {missingArgs.length > 0 && (
            <Badge bg="warning" text="dark" className="me-2">
              Missing args: {missingArgs.join(', ')}
            </Badge>
          )}
          {errorText && (
            <Alert variant="warning" className="chat-input-alert mb-0 py-2">
              {errorText}
            </Alert>
          )}
        </div>
      )}

      <Form onSubmit={handleSubmit}>
        <InputGroup>
          <Button variant="outline-secondary" type="button" onClick={() => fileRef.current?.click()} disabled={disabled || uploading}>
            <FiImage size={16} />
          </Button>

          <Button variant={recording ? 'danger' : 'outline-secondary'} type="button" onClick={toggleRecording} disabled={disabled || processingVoice}>
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
            style={{ resize: 'none', maxHeight: 120, overflow: 'auto' }}
          />

          <Button type="submit" variant="primary" disabled={disabled || (!text.trim() && attachments.length === 0)}>
            <FiSend size={16} />
          </Button>
        </InputGroup>

        <input ref={fileRef} type="file" accept="image/*" multiple hidden onChange={handlePickImages} />

        {(uploading || processingVoice || recording) && (
          <div className="mt-2 d-flex gap-2 align-items-center">
            {uploading && <Badge bg="secondary">Uploading image...</Badge>}
            {recording && <Badge bg="danger">Recording...</Badge>}
            {processingVoice && <Badge bg="info">Transcribing...</Badge>}
          </div>
        )}
      </Form>
    </div>
  );
}
