import { useEffect, useRef, useState } from 'react';
import { Button, Form } from 'react-bootstrap';
import { FiMic, FiPaperclip, FiSend, FiSquare, FiX } from 'react-icons/fi';

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

  const inputRef = useRef<HTMLTextAreaElement>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const recognitionRef = useRef<any>(null);

  useEffect(() => {
    if (!disabled) {
      inputRef.current?.focus();
    }
  }, [disabled]);

  useEffect(() => {
    return () => {
      if (recognitionRef.current) {
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
        id: crypto.randomUUID(),
        type: 'image',
        name: file.name,
        mimeType: file.type || 'image/png',
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
    if (disabled) {
      return;
    }
    const trimmed = text.trim();
    if (!trimmed && attachments.length === 0) {
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

  const handleKeyDown = (e: React.KeyboardEvent) => {
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

    const SpeechRecognitionCtor =
      (window as any).SpeechRecognition || (window as any).webkitSpeechRecognition;

    if (!SpeechRecognitionCtor) {
      return;
    }

    const recognition = new SpeechRecognitionCtor();
    recognition.continuous = false;
    recognition.interimResults = true;
    recognition.lang = navigator.language || 'en-US';

    recognition.onresult = (event: any) => {
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
      if (target) {
        URL.revokeObjectURL(target.previewUrl);
      }
      return prev.filter((item) => item.id !== id);
    });
  };

  const speechSupported =
    typeof window !== 'undefined' &&
    Boolean((window as any).SpeechRecognition || (window as any).webkitSpeechRecognition);

  return (
    <div className="chat-input-area">
      <Form onSubmit={handleSubmit}>
        <div
          className={`chat-input-shell d-flex align-items-end gap-2 ${isDragOver ? 'chat-drop-target-active' : ''}`}
          onDragOver={(e) => {
            e.preventDefault();
            if (!disabled) {
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
            onChange={(e) => setText(e.target.value)}
            onKeyDown={handleKeyDown}
            placeholder="Type a message or drop images..."
            disabled={disabled}
            className="chat-textarea"
          />

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
              disabled={disabled || !speechSupported}
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
            disabled={disabled || (!text.trim() && attachments.length === 0)}
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
          Enter to send, Shift+Enter for new line, drag and drop images to attach.
        </small>
      </Form>
    </div>
  );
}
