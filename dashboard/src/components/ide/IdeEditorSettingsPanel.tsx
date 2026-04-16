import type { ReactElement } from 'react';
import { FiSettings } from 'react-icons/fi';

export interface IdeEditorSettingsPanelProps {
  show: boolean;
  fontSize: number;
  wordWrap: boolean;
  minimap: boolean;
  onFontSizeChange: (fontSize: number) => void;
  onWordWrapChange: (wordWrap: boolean) => void;
  onMinimapChange: (minimap: boolean) => void;
}

export function IdeEditorSettingsPanel({
  show,
  fontSize,
  wordWrap,
  minimap,
  onFontSizeChange,
  onWordWrapChange,
  onMinimapChange,
}: IdeEditorSettingsPanelProps): ReactElement | null {
  if (!show) {
    return null;
  }

  return (
    <div className="ide-editor-settings border-b border-border/80 bg-muted/20 px-4 py-3">
      <div className="mb-2 flex items-center gap-2 text-xs font-semibold uppercase tracking-[0.16em] text-muted-foreground">
        <FiSettings size={13} />
        Editor settings
      </div>
      <div className="flex flex-wrap items-center gap-4 text-sm">
        <label className="flex items-center gap-2">
          <span className="text-muted-foreground">Font size</span>
          <input
            className="h-8 w-20 rounded-lg border border-border/80 bg-background px-2"
            type="number"
            min={11}
            max={22}
            value={fontSize}
            onChange={(event) => onFontSizeChange(Number.parseInt(event.target.value, 10))}
          />
        </label>
        <label className="flex items-center gap-2">
          <input type="checkbox" checked={wordWrap} onChange={(event) => onWordWrapChange(event.target.checked)} />
          <span>Word wrap</span>
        </label>
        <label className="flex items-center gap-2">
          <input type="checkbox" checked={minimap} onChange={(event) => onMinimapChange(event.target.checked)} />
          <span>Minimap</span>
        </label>
      </div>
    </div>
  );
}
