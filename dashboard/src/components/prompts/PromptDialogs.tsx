import { type MouseEvent, type ReactElement, type ReactNode, useEffect } from 'react';
import { createPortal } from 'react-dom';
import { FiX } from 'react-icons/fi';
import { cn } from '../../lib/utils';
import { Button } from '../ui/button';

interface PromptDialogFrameProps {
  show: boolean;
  title: string;
  size?: 'sm' | 'md';
  onCancel: () => void;
  children: ReactNode;
  footer: ReactNode;
}

function PromptDialogFrame({
  show,
  title,
  size = 'sm',
  onCancel,
  children,
  footer,
}: PromptDialogFrameProps): ReactElement {
  useEffect(() => {
    // Lock page scrolling while the prompt dialog is active so the overlay feels modal.
    if (!show) {
      return;
    }

    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';

    return () => {
      document.body.style.overflow = previousOverflow;
    };
  }, [show]);

  useEffect(() => {
    // Keep Escape available as a predictable dismissal shortcut for prompt dialogs.
    if (!show) {
      return;
    }

    const handleKeyDown = (event: KeyboardEvent): void => {
      if (event.key === 'Escape') {
        onCancel();
      }
    };

    window.addEventListener('keydown', handleKeyDown);

    return () => {
      window.removeEventListener('keydown', handleKeyDown);
    };
  }, [onCancel, show]);

  if (!show) {
    return <></>;
  }

  const handleSurfaceClick = (event: MouseEvent<HTMLDivElement>): void => {
    event.stopPropagation();
  };

  return createPortal(
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4" onClick={onCancel}>
      <div className="absolute inset-0 bg-slate-950/75 backdrop-blur-sm" aria-hidden="true" />
      <div
        role="dialog"
        aria-modal="true"
        aria-label={title}
        onClick={handleSurfaceClick}
        className={cn(
          'relative z-10 w-full rounded-[1.75rem] border border-border/80 bg-card/95 shadow-2xl backdrop-blur-xl',
          size === 'sm' ? 'max-w-md' : 'max-w-xl'
        )}
      >
        <div className="flex items-start justify-between gap-4 border-b border-border/80 px-6 py-5">
          <div>
            <h2 className="text-lg font-semibold tracking-tight text-foreground">{title}</h2>
          </div>
          <button
            type="button"
            onClick={onCancel}
            className="inline-flex h-9 w-9 items-center justify-center rounded-xl border border-border/80 bg-card/70 text-muted-foreground transition-colors duration-200 hover:bg-muted hover:text-foreground"
            aria-label="Close dialog"
          >
            <FiX size={16} />
          </button>
        </div>

        <div className="px-6 py-5 text-sm leading-6 text-muted-foreground">{children}</div>

        <div className="flex flex-wrap justify-end gap-2 border-t border-border/80 px-6 py-4">
          {footer}
        </div>
      </div>
    </div>,
    document.body
  );
}

export interface PromptDeleteDialogProps {
  show: boolean;
  promptName: string;
  isProcessing: boolean;
  onConfirm: () => void;
  onCancel: () => void;
}

export function PromptDeleteDialog({
  show,
  promptName,
  isProcessing,
  onConfirm,
  onCancel,
}: PromptDeleteDialogProps): ReactElement {
  return (
    <PromptDialogFrame
      show={show}
      title="Delete prompt"
      onCancel={onCancel}
      footer={
        <>
          <Button variant="secondary" onClick={onCancel} disabled={isProcessing}>
            Cancel
          </Button>
          <Button variant="destructive" onClick={onConfirm} disabled={isProcessing}>
            {isProcessing ? 'Deleting...' : 'Delete'}
          </Button>
        </>
      }
    >
      <p>
        Delete <strong className="text-foreground">{promptName}</strong>? This removes the section from the prompt
        catalog immediately.
      </p>
    </PromptDialogFrame>
  );
}

export interface PromptUnsavedChangesDialogProps {
  show: boolean;
  promptName: string;
  isSaving: boolean;
  onSaveAndContinue: () => void;
  onDiscardAndContinue: () => void;
  onCancel: () => void;
}

export function PromptUnsavedChangesDialog({
  show,
  promptName,
  isSaving,
  onSaveAndContinue,
  onDiscardAndContinue,
  onCancel,
}: PromptUnsavedChangesDialogProps): ReactElement {
  return (
    <PromptDialogFrame
      show={show}
      title="Unsaved changes"
      onCancel={onCancel}
      footer={
        <>
          <Button variant="secondary" onClick={onCancel} disabled={isSaving}>
            Stay here
          </Button>
          <Button variant="ghost" onClick={onDiscardAndContinue} disabled={isSaving}>
            Discard changes
          </Button>
          <Button onClick={onSaveAndContinue} disabled={isSaving}>
            {isSaving ? 'Saving...' : 'Save and continue'}
          </Button>
        </>
      }
    >
      <p>
        <strong className="text-foreground">{promptName}</strong> has unsaved edits. Save before continuing?
      </p>
    </PromptDialogFrame>
  );
}
