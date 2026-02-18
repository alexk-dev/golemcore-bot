import { useCallback, useEffect, useRef, useState } from 'react';
import { createUuid } from '../../utils/uuid';
import type { ChatAttachmentDraft } from './chatInputTypes';

function fileToDataUrl(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result ?? ''));
    reader.onerror = () => reject(new Error('Failed to read file'));
    reader.readAsDataURL(file);
  });
}

const MAX_ATTACHMENTS = 6;

interface ChatAttachmentsHook {
  attachments: ChatAttachmentDraft[];
  addImageFiles: (files: File[]) => Promise<void>;
  removeAttachment: (id: string) => void;
  clearAttachments: () => void;
  handlePaste: (e: React.ClipboardEvent) => void;
}

export function useChatAttachments(): ChatAttachmentsHook {
  const [attachments, setAttachments] = useState<ChatAttachmentDraft[]>([]);
  const attachmentsRef = useRef(attachments);
  attachmentsRef.current = attachments;

  // Revoke object URLs on unmount
  useEffect(() => {
    return () => {
      attachmentsRef.current.forEach((a) => URL.revokeObjectURL(a.previewUrl));
    };
  }, []);

  const clearAttachments = useCallback(() => {
    setAttachments((prev) => {
      prev.forEach((a) => URL.revokeObjectURL(a.previewUrl));
      return [];
    });
  }, []);

  const addImageFiles = useCallback(async (files: File[]) => {
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
      setAttachments((prev) => [...prev, ...prepared].slice(0, MAX_ATTACHMENTS));
    }
  }, []);

  const removeAttachment = useCallback((id: string) => {
    setAttachments((prev) => {
      const target = prev.find((item) => item.id === id);
      if (target !== undefined) {
        URL.revokeObjectURL(target.previewUrl);
      }
      return prev.filter((item) => item.id !== id);
    });
  }, []);

  const handlePaste = useCallback((e: React.ClipboardEvent) => {
    const items = e.clipboardData.items;
    const imageFiles: File[] = [];
    for (let i = 0; i < items.length; i += 1) {
      const item = items[i];
      if (item.type.startsWith('image/')) {
        const file = item.getAsFile();
        if (file !== null) {
          imageFiles.push(file);
        }
      }
    }
    if (imageFiles.length > 0) {
      e.preventDefault();
      void addImageFiles(imageFiles);
    }
  }, [addImageFiles]);

  return { attachments, addImageFiles, removeAttachment, clearAttachments, handlePaste };
}
