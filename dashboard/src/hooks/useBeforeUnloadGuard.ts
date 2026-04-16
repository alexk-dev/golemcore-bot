import { useEffect } from 'react';

export function useBeforeUnloadGuard(isDirty: boolean): void {
  useEffect(() => {
    // Warn the user via the browser's native dialog if they try to close the tab or reload with unsaved changes.
    if (!isDirty) {
      return;
    }
    const handler = (event: BeforeUnloadEvent): void => {
      event.preventDefault();
    };
    window.addEventListener('beforeunload', handler);
    return () => {
      window.removeEventListener('beforeunload', handler);
    };
  }, [isDirty]);
}
