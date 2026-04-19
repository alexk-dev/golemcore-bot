import { useEffect, useMemo, useState, type ReactElement } from 'react';
import CodeMirror from '@uiw/react-codemirror';
import { showMinimap as codeMirrorShowMinimap } from '@replit/codemirror-minimap';
import type { Extension } from '@codemirror/state';
import { EditorView, type ViewUpdate } from '@codemirror/view';
import { search } from '@codemirror/search';
import { useThemeStore } from '../../store/themeStore';
import { loadEditorLanguage, resolveEditorLanguage } from './codeEditorLanguages';

export interface CodeEditorSelection {
  from: number;
  to: number;
  selectedText: string;
}

export interface CodeEditorProps {
  filePath: string | null;
  value: string;
  onChange: (value: string) => void;
  onCursorChange?: (line: number, column: number) => void;
  onSelectionChange?: (selection: CodeEditorSelection | null) => void;
  showMinimap?: boolean;
  wordWrap?: boolean;
  fontSize?: number;
  searchQuery?: string;
}

function createCursorUpdateExtension(
  onCursorChange?: (line: number, column: number) => void,
  onSelectionChange?: (selection: CodeEditorSelection | null) => void,
): Extension {
  return EditorView.updateListener.of((update: ViewUpdate) => {
    if (onCursorChange != null) {
      if (update.docChanged || update.selectionSet || update.focusChanged || update.viewportChanged) {
        const head = update.state.selection.main.head;
        const line = update.state.doc.lineAt(head);
        const column = head - line.from + 1;
        onCursorChange(line.number, column);
      }
    }

    if (onSelectionChange != null && (update.selectionSet || update.docChanged)) {
      const selection = update.state.selection.main;
      if (selection.empty) {
        onSelectionChange(null);
        return;
      }
      onSelectionChange({
        from: selection.from,
        to: selection.to,
        selectedText: update.state.sliceDoc(selection.from, selection.to),
      });
    }
  });
}

function createMinimapExtension(): Extension {
  return codeMirrorShowMinimap.compute(['doc'], () => {
    return {
      create: () => {
        const dom = document.createElement('div');
        dom.className = 'ide-editor-minimap';
        return { dom };
      },
      displayText: 'blocks',
      showOverlay: 'mouse-over',
    };
  });
}

function createFontSizeExtension(fontSize: number): Extension {
  return EditorView.theme({
    '&': {
      fontSize: `${fontSize}px`,
    },
  });
}

function useLanguageExtension(filePath: string | null): Extension | null {
  const languageName = useMemo(() => resolveEditorLanguage(filePath), [filePath]);
  const [languageExtension, setLanguageExtension] = useState<Extension | null>(null);

  useEffect(() => {
    // Load syntax support separately so the editor shell does not include every language parser.
    let isCancelled = false;
    setLanguageExtension(null);

    if (languageName == null) {
      return () => {
        isCancelled = true;
      };
    }

    loadEditorLanguage(languageName)
      .then((extension) => {
        if (!isCancelled) {
          setLanguageExtension(extension);
        }
      })
      .catch((error: unknown) => {
        if (!isCancelled) {
          console.error('Failed to load editor language support.', error);
        }
      });

    return () => {
      isCancelled = true;
    };
  }, [languageName]);

  return languageExtension;
}

export function CodeEditor({
  filePath,
  value,
  onChange,
  onCursorChange,
  onSelectionChange,
  showMinimap = false,
  wordWrap = true,
  fontSize = 14,
  searchQuery = '',
}: CodeEditorProps): ReactElement {
  const theme = useThemeStore((state) => state.theme);
  const languageExtension = useLanguageExtension(filePath);

  const extensions = useMemo((): Extension[] => {
    const result: Extension[] = [
      createFontSizeExtension(fontSize),
      search({ top: true }),
      createCursorUpdateExtension(onCursorChange, onSelectionChange),
    ];

    if (wordWrap) {
      result.push(EditorView.lineWrapping);
    }

    if (languageExtension != null) {
      result.push(languageExtension);
    }

    if (showMinimap) {
      result.push(createMinimapExtension());
    }

    result.push(EditorView.theme({
      '&': {
        height: '100%',
      },
      '.cm-scroller': {
        overflow: 'auto',
      },
    }));

    return result;
  }, [fontSize, languageExtension, onCursorChange, onSelectionChange, showMinimap, wordWrap]);

  return (
    <div
      className="ide-editor h-full min-h-0 overflow-hidden"
      id="ide-editor-panel"
      role="tabpanel"
      aria-label="Code editor"
      data-search-query={searchQuery}
    >
      <CodeMirror
        value={value}
        height="100%"
        theme={theme === 'dark' ? 'dark' : 'light'}
        basicSetup={{
          lineNumbers: true,
          highlightActiveLine: true,
          foldGutter: true,
          autocompletion: true,
        }}
        extensions={extensions}
        onChange={onChange}
      />
    </div>
  );
}
