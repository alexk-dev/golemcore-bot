import { useCallback, useEffect, useState } from 'react';

export interface ElementSize {
  width: number;
  height: number;
}

export function useElementSize(): {
  ref: (node: HTMLDivElement | null) => void;
  size: ElementSize;
} {
  const [element, setElement] = useState<HTMLDivElement | null>(null);
  const [size, setSize] = useState<ElementSize>({ width: 320, height: 480 });

  const ref = useCallback((node: HTMLDivElement | null) => {
    setElement(node);
  }, []);

  useEffect(() => {
    // Keep virtualized tree dimensions in sync with the panel container.
    if (element == null) {
      return;
    }

    const observer = new ResizeObserver((entries: ResizeObserverEntry[]) => {
      const first = entries[0];
      if (first == null) {
        return;
      }
      const nextWidth = Math.floor(first.contentRect.width);
      const nextHeight = Math.floor(first.contentRect.height);
      setSize({ width: nextWidth, height: nextHeight });
    });

    observer.observe(element);
    return () => {
      observer.disconnect();
    };
  }, [element]);

  return { ref, size };
}
