import { useEffect, useState, type ReactElement } from 'react';
import type { TreeActionState } from '../../hooks/useIdeTreeActions';
import { Button } from '../ui/button';
import { Modal } from '../ui/bootstrap-overlay';
import { Input } from '../ui/field';

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
    return 'File path';
  }

  if (action.mode === 'rename') {
    return 'New path';
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
    <Modal show={action != null} onHide={onCancel} centered size="sm">
      <Modal.Header closeButton>
        <Modal.Title>{buildTitle(action)}</Modal.Title>
      </Modal.Header>
      <Modal.Body>
        <div className="mb-3 text-sm leading-6 text-muted-foreground">{buildMessage(action)}</div>
        {action != null && action.mode !== 'delete' && (
          <label htmlFor="ide-tree-action-input" className="block">
            <span className="mb-2 block text-sm font-medium text-foreground">{buildInputLabel(action)}</span>
            <Input
              id="ide-tree-action-input"
              autoFocus
              value={inputValue}
              onChange={(event) => setInputValue(event.target.value)}
              placeholder={action.mode === 'create' ? 'src/example.ts' : 'src/new-name.ts'}
            />
          </label>
        )}
      </Modal.Body>
      <Modal.Footer>
        <Button variant="secondary" onClick={onCancel} disabled={isProcessing}>
          Cancel
        </Button>
        <Button
          variant={action?.mode === 'delete' ? 'destructive' : 'default'}
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
