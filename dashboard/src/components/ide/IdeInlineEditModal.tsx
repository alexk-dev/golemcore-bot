import { useEffect, useRef, useState, type ReactElement } from 'react';
import { FiCommand } from 'react-icons/fi';
import { Modal } from '../ui/overlay';
import { Button } from '../ui/button';
import { Textarea } from '../ui/field';

export interface IdeInlineEditModalProps {
  show: boolean;
  selectedText: string;
  isSubmitting: boolean;
  onClose: () => void;
  onSubmit: (instruction: string) => void;
}

export function IdeInlineEditModal({
  show,
  selectedText,
  isSubmitting,
  onClose,
  onSubmit,
}: IdeInlineEditModalProps): ReactElement {
  const [instruction, setInstruction] = useState('');
  const textareaRef = useRef<HTMLTextAreaElement | null>(null);

  useEffect(() => {
    // Reset the inline edit composer each time the modal opens for a new selection.
    if (!show) {
      setInstruction('');
      return;
    }
    setInstruction('');
    window.setTimeout(() => textareaRef.current?.focus(), 0);
  }, [show, selectedText]);

  const handleSubmit = (): void => {
    const trimmedInstruction = instruction.trim();
    if (trimmedInstruction.length === 0) {
      return;
    }
    onSubmit(trimmedInstruction);
  };

  return (
    <Modal show={show} onHide={onClose} centered size="lg">
      <Modal.Header closeButton>
        <Modal.Title>Inline edit</Modal.Title>
      </Modal.Header>
      <Modal.Body>
        <div className="space-y-4">
          <div className="rounded-2xl border border-border/80 bg-muted/30 px-4 py-3 text-sm text-muted-foreground">
            <div className="mb-2 inline-flex items-center gap-2 font-semibold text-foreground">
              <FiCommand size={14} />
              Selected code
            </div>
            <pre className="mb-0 whitespace-pre-wrap break-words text-xs">{selectedText}</pre>
          </div>
          <div className="space-y-2">
            <label className="form-label mb-0" htmlFor="ide-inline-edit-instruction">Instruction</label>
            <Textarea
              id="ide-inline-edit-instruction"
              ref={textareaRef}
              className="min-h-[8.5rem]"
              placeholder="Refactor this code, add validation, improve naming, ..."
              value={instruction}
              onChange={(event) => setInstruction(event.target.value)}
            />
          </div>
        </div>
      </Modal.Body>
      <Modal.Footer>
        <Button type="button" variant="secondary" onClick={onClose} disabled={isSubmitting}>
          Cancel
        </Button>
        <Button type="button" onClick={handleSubmit} disabled={isSubmitting || instruction.trim().length === 0}>
          {isSubmitting ? 'Generating…' : 'Generate edit'}
        </Button>
      </Modal.Footer>
    </Modal>
  );
}
