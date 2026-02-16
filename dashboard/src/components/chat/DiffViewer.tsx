interface DiffViewerProps {
  diffText: string;
}

type DiffLineKind = 'meta' | 'hunk' | 'add' | 'del' | 'context';

interface DiffLine {
  kind: DiffLineKind;
  text: string;
}

function classifyLine(line: string): DiffLine {
  if (
    line.startsWith('diff --git') ||
    line.startsWith('index ') ||
    line.startsWith('--- ') ||
    line.startsWith('+++ ') ||
    line.startsWith('new file mode') ||
    line.startsWith('deleted file mode') ||
    line.startsWith('rename from ') ||
    line.startsWith('rename to ')
  ) {
    return { kind: 'meta', text: line };
  }
  if (line.startsWith('@@')) {
    return { kind: 'hunk', text: line };
  }
  if (line.startsWith('+')) {
    return { kind: 'add', text: line };
  }
  if (line.startsWith('-')) {
    return { kind: 'del', text: line };
  }
  return { kind: 'context', text: line };
}

export default function DiffViewer({ diffText }: DiffViewerProps) {
  const lines = diffText.split('\n').map(classifyLine);

  return (
    <div className="gc-diff-viewer">
      <div className="gc-diff-header">Patch preview</div>
      <div className="gc-diff-body">
        {lines.map((line, i) => (
          <div key={i} className={`gc-diff-line ${line.kind}`}>
            <span className="gc-diff-ln">{i + 1}</span>
            <code>{line.text || ' '}</code>
          </div>
        ))}
      </div>
    </div>
  );
}
