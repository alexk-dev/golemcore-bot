import type { Extension } from '@codemirror/state';

export type LanguageKey =
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

type LanguageLoader = () => Promise<Extension | null>;

const LANGUAGE_ALIAS: Record<string, LanguageKey> = {
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

const LANGUAGE_LOADERS: Record<LanguageKey, LanguageLoader> = {
  java: async () => (await import('@codemirror/lang-java')).java(),
  js: async () => (await import('@codemirror/lang-javascript')).javascript(),
  jsx: async () => (await import('@codemirror/lang-javascript')).javascript({ jsx: true }),
  ts: async () => (await import('@codemirror/lang-javascript')).javascript({ typescript: true }),
  tsx: async () => (await import('@codemirror/lang-javascript')).javascript({ jsx: true, typescript: true }),
  json: async () => (await import('@codemirror/lang-json')).json(),
  markdown: async () => (await import('@codemirror/lang-markdown')).markdown(),
  yml: async () => (await import('@codemirror/lang-yaml')).yaml(),
  yaml: async () => (await import('@codemirror/lang-yaml')).yaml(),
  xml: async () => (await import('@codemirror/lang-xml')).xml(),
  html: async () => (await import('@codemirror/lang-html')).html(),
  css: async () => (await import('@codemirror/lang-css')).css(),
  scss: async () => (await import('@codemirror/lang-sass')).sass(),
  bash: async () => {
    const [languageModule, shellModule] = await Promise.all([
      import('@codemirror/language'),
      import('@codemirror/legacy-modes/mode/shell'),
    ]);
    return languageModule.StreamLanguage.define(shellModule.shell);
  },
  py: async () => (await import('@codemirror/lang-python')).python(),
  go: async () => (await import('@codemirror/lang-go')).go(),
  rs: async () => (await import('@codemirror/lang-rust')).rust(),
  kt: async () => {
    const [languageModule, clikeModule] = await Promise.all([
      import('@codemirror/language'),
      import('@codemirror/legacy-modes/mode/clike'),
    ]);
    return languageModule.StreamLanguage.define(clikeModule.kotlin);
  },
  c: async () => {
    const [languageModule, clikeModule] = await Promise.all([
      import('@codemirror/language'),
      import('@codemirror/legacy-modes/mode/clike'),
    ]);
    return languageModule.StreamLanguage.define(clikeModule.c);
  },
  h: async () => {
    const [languageModule, clikeModule] = await Promise.all([
      import('@codemirror/language'),
      import('@codemirror/legacy-modes/mode/clike'),
    ]);
    return languageModule.StreamLanguage.define(clikeModule.c);
  },
  cpp: async () => (await import('@codemirror/lang-cpp')).cpp(),
  cxx: async () => (await import('@codemirror/lang-cpp')).cpp(),
  cs: async () => {
    const [languageModule, clikeModule] = await Promise.all([
      import('@codemirror/language'),
      import('@codemirror/legacy-modes/mode/clike'),
    ]);
    return languageModule.StreamLanguage.define(clikeModule.csharp);
  },
  php: async () => (await import('@codemirror/lang-php')).php(),
  vue: async () => (await import('@codemirror/lang-vue')).vue(),
  sql: async () => (await import('@codemirror/lang-sql')).sql(),
  toml: async () => {
    const [languageModule, tomlModule] = await Promise.all([
      import('@codemirror/language'),
      import('@codemirror/legacy-modes/mode/toml'),
    ]);
    return languageModule.StreamLanguage.define(tomlModule.toml);
  },
  ini: async () => {
    const [languageModule, propertiesModule] = await Promise.all([
      import('@codemirror/language'),
      import('@codemirror/legacy-modes/mode/properties'),
    ]);
    return languageModule.StreamLanguage.define(propertiesModule.properties);
  },
  text: () => Promise.resolve(null),
};

export function resolveEditorLanguage(path: string | null): LanguageKey | null {
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

  return LANGUAGE_ALIAS[extension] ?? null;
}

export function loadEditorLanguage(languageName: LanguageKey): Promise<Extension | null> {
  return LANGUAGE_LOADERS[languageName]();
}
