import type { ReactElement } from 'react';
import { FiInfo } from 'react-icons/fi';

export interface EditorStatusBarProps {
  activePath: string | null;
  line: number;
  column: number;
  language: string;
  fileSizeBytes: number;
  updatedAt: string | null;
}

function formatFileSize(fileSizeBytes: number): string {
  if (fileSizeBytes < 1024) {
    return `${fileSizeBytes} B`;
  }
  if (fileSizeBytes < 1024 * 1024) {
    return `${(fileSizeBytes / 1024).toFixed(1)} KB`;
  }
  return `${(fileSizeBytes / (1024 * 1024)).toFixed(2)} MB`;
}

function formatUpdatedAt(updatedAt: string | null): string {
  if (updatedAt == null || updatedAt.length === 0) {
    return 'Unsaved';
  }

  const parsed = new Date(updatedAt);
  if (Number.isNaN(parsed.getTime())) {
    return 'Unsaved';
  }

  return parsed.toLocaleString();
}

export function EditorStatusBar({
  activePath,
  line,
  column,
  language,
  fileSizeBytes,
  updatedAt,
}: EditorStatusBarProps): ReactElement {
  return (
    <div className="ide-statusbar flex min-h-[2.5rem] flex-col gap-2 border-b border-border/80 px-4 py-2 text-xs text-muted-foreground sm:flex-row sm:items-center sm:justify-between">
      <div className="min-w-0 max-w-full truncate pr-2" title={activePath ?? ''}>
        {activePath ?? 'No file selected'}
      </div>
      <div className="flex flex-wrap items-center gap-2 sm:gap-3 sm:justify-end">
        <span className="rounded-full border border-border/80 bg-background/80 px-2 py-1 font-medium text-foreground" title={language}>
          {language.toUpperCase()}
        </span>
        <span>{formatFileSize(fileSizeBytes)}</span>
        <span title={updatedAt ?? ''}>Saved: {formatUpdatedAt(updatedAt)}</span>
        <span>
          Ln {line}, Col {column}
        </span>
        <span className="hidden items-center gap-1 xl:inline-flex">
          <FiInfo size={14} />
          <span>Ctrl/Cmd + S save · Ctrl/Cmd + P quick open</span>
        </span>
      </div>
    </div>
  );
}

export interface EditorContentStateProps {
  isFileOpening: boolean;
  hasFileLoadError: boolean;
  hasActiveTab: boolean;
  onRetry: () => void;
  children: ReactElement;
}

export function EditorContentState({
  isFileOpening,
  hasFileLoadError,
  hasActiveTab,
  onRetry,
  children,
}: EditorContentStateProps): ReactElement {
  if (isFileOpening) {
    return (
      <div className="flex h-full items-center justify-center gap-2 text-sm text-muted-foreground">
        <span
          className="h-4 w-4 animate-spin rounded-full border-2 border-border border-t-primary"
          role="status"
          aria-hidden
        />
        <span>Opening file...</span>
      </div>
    );
  }

  if (hasFileLoadError) {
    return (
      <div
        className="m-4 flex items-center justify-between gap-3 rounded-2xl border border-destructive/30 bg-destructive/10 px-4 py-3 text-sm text-destructive"
        role="alert"
      >
        <span>Failed to load file content.</span>
        <button
          type="button"
          className="inline-flex h-8 items-center justify-center rounded-lg border border-destructive/40 px-3 text-xs font-semibold text-destructive transition-colors hover:bg-destructive/10"
          onClick={onRetry}
        >
          Retry
        </button>
      </div>
    );
  }

  if (!hasActiveTab) {
    return (
      <div className="flex h-full items-center justify-center text-sm text-muted-foreground">
        Open a file from the tree to start editing.
      </div>
    );
  }

  return children;
}
