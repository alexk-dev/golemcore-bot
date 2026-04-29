import { useEffect } from 'react';
import { useCommandPaletteStore } from '../../store/commandPaletteStore';

function isPaletteShortcut(event: KeyboardEvent): boolean {
  const key = event.key.toLowerCase();
  return (event.metaKey || event.ctrlKey) && key === 'k';
}

export function useCommandPaletteShortcut(): void {
  const togglePalette = useCommandPaletteStore((s) => s.togglePalette);

  // Bind a single window-level listener that opens/closes the palette on Cmd/Ctrl+K.
  useEffect(() => {
    function handleKeyDown(event: KeyboardEvent): void {
      if (!isPaletteShortcut(event)) {
        return;
      }
      event.preventDefault();
      togglePalette();
    }
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [togglePalette]);
}
