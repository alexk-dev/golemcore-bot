import type { ReactElement } from 'react';
import { Button } from '../ui/button';
import { Modal } from '../ui/overlay';

export interface UnsavedChangesModalProps {
  show: boolean;
  fileTitle: string;
  isProcessing: boolean;
  onSaveAndClose: () => void;
  onCloseWithoutSaving: () => void;
  onCancel: () => void;
}

export function UnsavedChangesModal({
  show,
  fileTitle,
  isProcessing,
  onSaveAndClose,
  onCloseWithoutSaving,
  onCancel,
}: UnsavedChangesModalProps): ReactElement {
  return (
    <Modal show={show} onHide={onCancel} centered size="sm">
      <Modal.Header closeButton>
        <Modal.Title>Unsaved changes</Modal.Title>
      </Modal.Header>
      <Modal.Body className="text-sm leading-6 text-muted-foreground">
        File <strong className="text-foreground">{fileTitle}</strong> has unsaved changes.
        Do you want to save before closing?
      </Modal.Body>
      <Modal.Footer>
        <Button variant="secondary" onClick={onCancel} disabled={isProcessing}>
          Cancel
        </Button>
        <Button variant="destructive" onClick={onCloseWithoutSaving} disabled={isProcessing}>
          Close without saving
        </Button>
        <Button variant="default" onClick={onSaveAndClose} disabled={isProcessing}>
          {isProcessing ? 'Saving...' : 'Save and close'}
        </Button>
      </Modal.Footer>
    </Modal>
  );
}
