import { type ReactElement, useMemo } from 'react';
import CodeMirror, { type Extension } from '@uiw/react-codemirror';
import { EditorView } from '@codemirror/view';
import { loadLanguage } from '@uiw/codemirror-extensions-langs';
import { useThemeStore } from '../../store/themeStore';

export interface CodeEditorProps {
  filePath: string | null;
  value: string;
  onChange: (value: string) => void;
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

export function CodeEditor({ filePath, value, onChange }: CodeEditorProps): ReactElement {
  const theme = useThemeStore((state) => state.theme);

  const extensions = useMemo((): Extension[] => {
    const languageName = resolveLanguageName(filePath);
    const loadedLanguage = languageName == null ? null : loadLanguage(languageName);
    const result: Extension[] = [EditorView.lineWrapping];

    if (loadedLanguage != null) {
      result.push(loadedLanguage);
    }

    return result;
  }, [filePath]);

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
