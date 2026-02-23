import type { ReactElement } from 'react';
import { FiInfo } from 'react-icons/fi';

export interface EditorStatusBarProps {
  activePath: string | null;
  line: number;
  column: number;
}

export function EditorStatusBar({ activePath, line, column }: EditorStatusBarProps): ReactElement {
  return (
    <div className="ide-statusbar d-flex align-items-center justify-content-between px-3 py-2 border-bottom small">
      <div className="text-truncate pe-2" title={activePath ?? ''}>
        {activePath ?? 'No file selected'}
      </div>
      <div className="text-body-secondary d-flex align-items-center gap-3">
        <span>
          Ln {line}, Col {column}
        </span>
        <span className="d-flex align-items-center gap-1">
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
      <div className="h-100 d-flex align-items-center justify-content-center text-body-secondary gap-2">
        <span className="spinner-border spinner-border-sm" role="status" aria-hidden />
        <span>Opening file...</span>
      </div>
    );
  }

  if (hasFileLoadError) {
    return (
      <div className="alert alert-danger m-3 d-flex align-items-center justify-content-between gap-3" role="alert">
        <span>Failed to load file content.</span>
        <button type="button" className="btn btn-sm btn-outline-danger" onClick={onRetry}>
          Retry
        </button>
      </div>
    );
  }

  if (!hasActiveTab) {
    return (
      <div className="h-100 d-flex align-items-center justify-content-center text-body-secondary">
        Open a file from the tree to start editing.
      </div>
    );
  }

  return children;
}
