import { useEffect, useRef, type ReactElement } from 'react';
import { MergeView } from '@codemirror/merge';
import { EditorState } from '@codemirror/state';
import { EditorView } from '@codemirror/view';
import type { ProposedEdit } from '../../store/proposedEditStore';

export interface InlineDiffViewProps {
  proposal: ProposedEdit;
  onAccept: (id: string) => void;
  onReject: (id: string) => void;
}

export function InlineDiffView({ proposal, onAccept, onReject }: InlineDiffViewProps): ReactElement {
  const hostRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    // Mount the CodeMirror merge view on the dedicated host div for this proposal.
    const host = hostRef.current;
    if (!host) {
      return undefined;
    }

    const view = new MergeView({
      parent: host,
      a: {
        doc: proposal.before,
        extensions: [EditorState.readOnly.of(true), EditorView.editable.of(false)],
      },
      b: {
        doc: proposal.after,
        extensions: [EditorState.readOnly.of(true), EditorView.editable.of(false)],
      },
    });

    return (): void => {
      view.destroy();
    };
  }, [proposal.before, proposal.after]);

  return (
    <div className="inline-diff-view">
      <div className="inline-diff-header">
        <span className="inline-diff-path">{proposal.path}</span>
        <div className="inline-diff-actions">
          <button
            type="button"
            data-testid="inline-diff-reject"
            className="inline-diff-button inline-diff-button--reject"
            onClick={() => onReject(proposal.id)}
          >
            Reject
          </button>
          <button
            type="button"
            data-testid="inline-diff-accept"
            className="inline-diff-button inline-diff-button--accept"
            onClick={() => onAccept(proposal.id)}
          >
            Accept
          </button>
        </div>
      </div>
      <div ref={hostRef} data-testid="inline-diff-merge" className="inline-diff-merge" />
    </div>
  );
}
