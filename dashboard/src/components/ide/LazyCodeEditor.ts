import { lazy, type LazyExoticComponent, type ReactElement } from 'react';

import type { CodeEditorProps } from './CodeEditor';

type CodeEditorComponent = (props: CodeEditorProps) => ReactElement;

export interface CodeEditorLoaderResult {
  default: CodeEditorComponent;
}

export function loadCodeEditor(): Promise<CodeEditorLoaderResult> {
  return import('./CodeEditor').then((module): CodeEditorLoaderResult => ({
    default: module.CodeEditor,
  }));
}

export const LazyCodeEditor: LazyExoticComponent<CodeEditorComponent> = lazy(loadCodeEditor);
