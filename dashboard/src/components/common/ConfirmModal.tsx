import type { ReactElement } from 'react';
import { Button } from '../ui/button';
import { Modal } from '../ui/overlay';

interface ConfirmModalProps {
  show: boolean;
  title: string;
  message: string;
  confirmLabel?: string;
  confirmVariant?: string;
  isProcessing?: boolean;
  onConfirm: () => void;
  onCancel: () => void;
}

function resolveConfirmVariant(confirmVariant: string): 'default' | 'warning' | 'destructive' {
  if (confirmVariant === 'warning') {
    return 'warning';
  }
  if (confirmVariant === 'danger' || confirmVariant === 'destructive') {
    return 'destructive';
  }
  return 'default';
}

export default function ConfirmModal({
  show,
  title,
  message,
  confirmLabel = 'Confirm',
  confirmVariant = 'danger',
  isProcessing = false,
  onConfirm,
  onCancel,
}: ConfirmModalProps): ReactElement {
  return (
    <Modal show={show} onHide={onCancel} centered size="sm">
      <Modal.Header closeButton>
        <Modal.Title>{title}</Modal.Title>
      </Modal.Header>
      <Modal.Body>
        <p className="text-sm leading-6 text-muted-foreground">{message}</p>
      </Modal.Body>
      <Modal.Footer>
        <Button type="button" variant="secondary" size="sm" onClick={onCancel} disabled={isProcessing}>
          Cancel
        </Button>
        <Button
          type="button"
          variant={resolveConfirmVariant(confirmVariant)}
          size="sm"
          onClick={onConfirm}
          disabled={isProcessing}
        >
          {isProcessing ? 'Processing...' : confirmLabel}
        </Button>
      </Modal.Footer>
    </Modal>
  );
}
