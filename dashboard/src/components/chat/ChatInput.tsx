import { useEffect, useMemo, useRef, useState } from 'react';
import { Badge, Button, Form, InputGroup } from 'react-bootstrap';
import { FiImage, FiMic, FiSend, FiX } from 'react-icons/fi';
import { useCommands } from '../../hooks/useCommands';
import CommandAutocomplete, { filterCommands } from './CommandAutocomplete';
import { uploadImages, ImageUploadResponse } from '../../api/uploads';
import { transcribeVoice } from '../../api/voice';

interface Props {
  onSend: (text: string) => void;
  disabled?: boolean;
}

export default function ChatInput({ onSend, disabled }: Props) {
  const [text, setText] = useState('');
  const [selectedCmdIndex, setSelectedCmdIndex] = useState(0);
  const [attachments, setAttachments] = useState<ImageUploadResponse[]>([]);
  const [uploading, setUploading] = useState(false);
  const [recording, setRecording] = useState(false);
  const [processingVoice, setProcessingVoice] = useState(false);

  const inputRef = useRef<HTMLTextAreaElement>(null);
  const fileRef = useRef<HTMLInputElement>(null);
  const mediaRecorderRef = useRef<MediaRecorder | null>(null);
  const mediaChunksRef = useRef<BlobPart[]>([]);

  const commandsQuery = useCommands();
  const commands = commandsQuery.data ?? [];
  const filteredCommands = useMemo(() => filterCommands(commands, text).slice(0, 8), [commands, text]);
  const showAutocomplete = text.startsWith('/') && text.trim().length > 0;

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
    const images = Array.from(files).filter((f) => f.type.startsWith('image/'));
    if (images.length === 0) return;

    try {
      setUploading(true);
      const uploaded = await uploadImages(images);
      setAttachments((prev) => [...prev, ...uploaded]);
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
      try {
        setProcessingVoice(true);
        const res = await transcribeVoice(blob);
        setText((prev) => (prev ? `${prev} ${res.text}` : res.text));
      } finally {
        setProcessingVoice(false);
      }
    };

    recorder.start();
    mediaRecorderRef.current = recorder;
    setRecording(true);
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
