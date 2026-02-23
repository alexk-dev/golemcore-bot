import { useCallback, useEffect, useState } from 'react';

export interface UseResizableSidebarOptions {
  initialWidth: number;
  minWidth: number;
  maxWidth: number;
  storageKey: string;
  cssVariableName: string;
}

export interface UseResizableSidebarResult {
  width: number;
  startResize: (clientX: number) => void;
  increase: () => void;
  decrease: () => void;
}

function clamp(value: number, min: number, max: number): number {
  if (value < min) {
    return min;
  }
  if (value > max) {
    return max;
  }
  return value;
}

function readInitialWidth(options: UseResizableSidebarOptions): number {
  const raw = localStorage.getItem(options.storageKey);
  if (raw == null) {
    return options.initialWidth;
  }

  const parsed = Number.parseInt(raw, 10);
  if (Number.isNaN(parsed)) {
    return options.initialWidth;
  }

  return clamp(parsed, options.minWidth, options.maxWidth);
}

export function useResizableSidebar(options: UseResizableSidebarOptions): UseResizableSidebarResult {
  const [width, setWidth] = useState<number>(() => readInitialWidth(options));

  useEffect(() => {
    // Persist preferred sidebar width between IDE sessions.
    localStorage.setItem(options.storageKey, String(width));
  }, [options.storageKey, width]);

  useEffect(() => {
    // Keep CSS width variable synchronized for layout rendering.
    document.documentElement.style.setProperty(options.cssVariableName, `${width}px`);
  }, [options.cssVariableName, width]);

  const startResize = useCallback((clientX: number): void => {
    const startWidth = width;

    const onMouseMove = (event: MouseEvent): void => {
      const delta = event.clientX - clientX;
      const nextWidth = clamp(startWidth + delta, options.minWidth, options.maxWidth);
      setWidth(nextWidth);
    };

    const onMouseUp = (): void => {
      window.removeEventListener('mousemove', onMouseMove);
      window.removeEventListener('mouseup', onMouseUp);
    };

    window.addEventListener('mousemove', onMouseMove);
    window.addEventListener('mouseup', onMouseUp);
  }, [options.maxWidth, options.minWidth, width]);

  const increase = useCallback((): void => {
    setWidth((current) => clamp(current + 24, options.minWidth, options.maxWidth));
  }, [options.maxWidth, options.minWidth]);

  const decrease = useCallback((): void => {
    setWidth((current) => clamp(current - 24, options.minWidth, options.maxWidth));
  }, [options.maxWidth, options.minWidth]);

  return {
    width,
    startResize,
    increase,
    decrease,
  };
}
