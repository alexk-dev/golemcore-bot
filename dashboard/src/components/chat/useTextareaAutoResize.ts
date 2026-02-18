import { useCallback, useRef } from 'react';

const MIN_HEIGHT = 46;
const MAX_HEIGHT = 200;

interface TextareaAutoResize {
  textareaRef: React.RefObject<HTMLTextAreaElement | null>;
  adjustHeight: () => void;
  resetHeight: () => void;
}

export function useTextareaAutoResize(): TextareaAutoResize {
  const textareaRef = useRef<HTMLTextAreaElement | null>(null);

  const adjustHeight = useCallback(() => {
    const el = textareaRef.current;
    if (el === null) {
      return;
    }
    el.style.height = `${MIN_HEIGHT}px`;
    const clamped = Math.min(el.scrollHeight, MAX_HEIGHT);
    el.style.height = `${clamped}px`;
  }, []);

  const resetHeight = useCallback(() => {
    const el = textareaRef.current;
    if (el === null) {
      return;
    }
    el.style.height = `${MIN_HEIGHT}px`;
  }, []);

  return { textareaRef, adjustHeight, resetHeight };
}
