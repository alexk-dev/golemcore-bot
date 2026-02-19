import { Button, Modal } from 'react-bootstrap';

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

export default function ConfirmModal({
  show,
  title,
  message,
  confirmLabel = 'Confirm',
  confirmVariant = 'danger',
  isProcessing = false,
  onConfirm,
  onCancel,
}: ConfirmModalProps) {
  return (
    <Modal show={show} onHide={onCancel} centered>
      <Modal.Header closeButton>
        <Modal.Title>{title}</Modal.Title>
      </Modal.Header>
      <Modal.Body className="text-body-secondary">{message}</Modal.Body>
      <Modal.Footer>
        <Button type="button" variant="secondary" onClick={onCancel} disabled={isProcessing}>
          Cancel
        </Button>
        <Button type="button" variant={confirmVariant} onClick={onConfirm} disabled={isProcessing}>
          {isProcessing ? 'Processing...' : confirmLabel}
        </Button>
      </Modal.Footer>
    </Modal>
  );
}
