import { useEffect, useState, type ReactElement } from 'react';
import { Button, Form, Modal } from 'react-bootstrap';
import type { TreeActionState } from '../../hooks/useIdeTreeActions';

export interface TreeActionModalProps {
  action: TreeActionState | null;
  isProcessing: boolean;
  onCancel: () => void;
  onCreate: (targetPath: string, nextPath: string) => void;
  onRename: (sourcePath: string, nextPath: string) => void;
  onDelete: (targetPath: string) => void;
}

function buildTitle(action: TreeActionState | null): string {
  if (action == null) {
    return '';
  }

  if (action.mode === 'create') {
    return 'New file';
  }

  if (action.mode === 'rename') {
    return 'Rename';
  }

  return 'Delete';
}

function buildMessage(action: TreeActionState | null): string {
  if (action == null) {
    return '';
  }

  if (action.mode === 'create') {
    const target = action.targetPath.length === 0 ? 'workspace root' : action.targetPath;
    return `Create a new file in ${target}.`;
  }

  if (action.mode === 'rename') {
    return `Rename ${action.targetPath}.`;
  }

  return `Delete ${action.targetPath}. This action cannot be undone.`;
}

function buildInputLabel(action: TreeActionState | null): string {
  if (action == null) {
    return '';
  }

  if (action.mode === 'create') {
    return 'File name';
  }

  if (action.mode === 'rename') {
    return 'New name';
  }

  return '';
}

export function TreeActionModal({
  action,
  isProcessing,
  onCancel,
  onCreate,
  onRename,
  onDelete,
}: TreeActionModalProps): ReactElement {
  const [inputValue, setInputValue] = useState<string>('');

  useEffect(() => {
    // Sync input value when action changes.
    setInputValue(action?.defaultValue ?? '');
  }, [action]);

  return (
    <Modal show={action != null} onHide={onCancel} centered>
      <Modal.Header closeButton>
        <Modal.Title>{buildTitle(action)}</Modal.Title>
      </Modal.Header>
      <Modal.Body>
        <div className="text-body-secondary mb-3">{buildMessage(action)}</div>
        {action != null && action.mode !== 'delete' && (
          <Form.Group controlId="ide-tree-action-input">
            <Form.Label>{buildInputLabel(action)}</Form.Label>
            <Form.Control
              autoFocus
              value={inputValue}
              onChange={(event) => setInputValue(event.target.value)}
              placeholder={action.mode === 'create' ? 'example.txt' : 'new-name.ext'}
            />
          </Form.Group>
        )}
      </Modal.Body>
      <Modal.Footer>
        <Button variant="secondary" onClick={onCancel} disabled={isProcessing}>
          Cancel
        </Button>
        <Button
          variant={action?.mode === 'delete' ? 'danger' : 'primary'}
          disabled={isProcessing}
          onClick={() => {
            if (action == null) {
              return;
            }

            if (action.mode === 'create') {
              onCreate(action.targetPath, inputValue);
              return;
            }

            if (action.mode === 'rename') {
              onRename(action.targetPath, inputValue);
              return;
            }

            onDelete(action.targetPath);
          }}
        >
          {isProcessing
            ? 'Processing...'
            : (action?.mode === 'delete' ? 'Delete' : 'Continue')}
        </Button>
      </Modal.Footer>
    </Modal>
  );
}
