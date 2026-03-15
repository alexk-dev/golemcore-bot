import { useCallback, useEffect, useState } from 'react';

export interface UseIdeMobileExplorerResult {
  isOpen: boolean;
  open: () => void;
  close: () => void;
  wrapAction: <Args extends unknown[]>(action: (...args: Args) => void) => (...args: Args) => void;
}

export function useIdeMobileExplorer(isMobileLayout: boolean): UseIdeMobileExplorerResult {
  const [isOpen, setIsOpen] = useState<boolean>(false);

  useEffect(() => {
    if (!isMobileLayout) {
      setIsOpen(false);
    }
  }, [isMobileLayout]);

  const close = useCallback((): void => {
    setIsOpen(false);
  }, []);

  const open = useCallback((): void => {
    setIsOpen(true);
  }, []);

  const wrapAction = useCallback(<Args extends unknown[]>(action: (...args: Args) => void) => {
    return (...args: Args): void => {
      action(...args);
      if (isMobileLayout) {
        close();
      }
    };
  }, [close, isMobileLayout]);

  return {
    isOpen,
    open,
    close,
    wrapAction,
  };
}
