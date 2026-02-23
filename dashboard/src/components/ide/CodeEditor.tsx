import { useMemo, type ReactElement } from 'react';
import CodeMirror from '@uiw/react-codemirror';
import { loadLanguage } from '@uiw/codemirror-extensions-langs';
import { showMinimap as codeMirrorShowMinimap } from '@replit/codemirror-minimap';
import type { Extension } from '@codemirror/state';
import { EditorView, type ViewUpdate } from '@codemirror/view';
import { useThemeStore } from '../../store/themeStore';

export interface CodeEditorProps {
  filePath: string | null;
  value: string;
  onChange: (value: string) => void;
  onCursorChange?: (line: number, column: number) => void;
  showMinimap?: boolean;
}

type LanguageKey =
  | 'java'
  | 'js'
  | 'jsx'
  | 'ts'
  | 'tsx'
  | 'json'
  | 'markdown'
  | 'yml'
  | 'yaml'
  | 'xml'
  | 'html'
  | 'css'
  | 'scss'
  | 'bash'
  | 'py'
  | 'go'
  | 'rs'
  | 'kt'
  | 'c'
  | 'h'
  | 'cpp'
  | 'cxx'
  | 'cs'
  | 'php'
  | 'vue'
  | 'sql'
  | 'toml'
  | 'ini'
  | 'text';

function resolveLanguageName(path: string | null): LanguageKey | null {
  if (path == null || path.length === 0) {
    return null;
  }

  const segments = path.split('/');
  const filename = segments[segments.length - 1] ?? '';
  if (!filename.includes('.')) {
    return null;
  }

  const extension = filename.split('.').pop()?.toLowerCase();
  if (extension == null) {
    return null;
  }

  const languageAlias: Record<string, LanguageKey> = {
    java: 'java',
    js: 'js',
    jsx: 'jsx',
    ts: 'ts',
    tsx: 'tsx',
    json: 'json',
    md: 'markdown',
    markdown: 'markdown',
    yml: 'yml',
    yaml: 'yaml',
    xml: 'xml',
    html: 'html',
    css: 'css',
    scss: 'scss',
    sh: 'bash',
    bash: 'bash',
    py: 'py',
    go: 'go',
    rs: 'rs',
    kt: 'kt',
    c: 'c',
    h: 'h',
    cpp: 'cpp',
    cxx: 'cxx',
    cs: 'cs',
    php: 'php',
    vue: 'vue',
    sql: 'sql',
    toml: 'toml',
    ini: 'ini',
    txt: 'text',
  };

  return languageAlias[extension] ?? null;
}

function createCursorUpdateExtension(onCursorChange?: (line: number, column: number) => void): Extension {
  return EditorView.updateListener.of((update: ViewUpdate) => {
    if (onCursorChange == null) {
      return;
    }

    if (!update.docChanged && !update.selectionSet && !update.focusChanged && !update.viewportChanged) {
      return;
    }

    const head = update.state.selection.main.head;
    const line = update.state.doc.lineAt(head);
    const column = head - line.from + 1;
    onCursorChange(line.number, column);
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

export function CodeEditor({
  filePath,
  value,
  onChange,
  onCursorChange,
  showMinimap = false,
}: CodeEditorProps): ReactElement {
  const theme = useThemeStore((state) => state.theme);

  const extensions = useMemo((): Extension[] => {
    const languageName = resolveLanguageName(filePath);
    const loadedLanguage = languageName == null ? null : loadLanguage(languageName);

    const result: Extension[] = [
      EditorView.lineWrapping,
      createCursorUpdateExtension(onCursorChange),
    ];

    if (loadedLanguage != null) {
      result.push(loadedLanguage);
    }

    if (showMinimap) {
      result.push(createMinimapExtension());
    }

    return result;
  }, [filePath, onCursorChange, showMinimap]);

  return (
    <div className="ide-editor h-100" id="ide-editor-panel" role="tabpanel" aria-label="Code editor">
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
