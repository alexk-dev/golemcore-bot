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
    return 'not saved';
  }

  const parsed = new Date(updatedAt);
  if (Number.isNaN(parsed.getTime())) {
    return 'not saved';
  }

  return parsed.toLocaleString();
}

function getLanguageIcon(language: string): string {
  const icons: Record<string, string> = {
    java: '☕',
    typescript: '🔷',
    javascript: '🟨',
    json: '🧩',
    markdown: '📝',
    yaml: '📘',
    xml: '🧷',
    html: '🌐',
    css: '🎨',
    scss: '🎨',
    bash: '💻',
    python: '🐍',
    go: '🐹',
    rust: '🦀',
    kotlin: '🟣',
    sql: '🗄️',
    toml: '⚙️',
    ini: '⚙️',
  };

  return icons[language] ?? '📄';
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
    <div className="ide-statusbar d-flex align-items-center justify-content-between px-3 py-2 border-bottom small">
      <div className="text-truncate pe-2" title={activePath ?? ''}>
        {activePath ?? 'No file selected'}
      </div>
      <div className="text-body-secondary d-flex align-items-center gap-3">
        <span title={language}>{getLanguageIcon(language)} {language.toUpperCase()}</span>
        <span>{formatFileSize(fileSizeBytes)}</span>
        <span title={updatedAt ?? ''}>Updated: {formatUpdatedAt(updatedAt)}</span>
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
