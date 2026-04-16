import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { fileURLToPath, URL } from 'node:url';

const EDITOR_BASE_PACKAGES = [
  '@codemirror/autocomplete',
  '@codemirror/commands',
  '@codemirror/language',
  '@codemirror/lint',
  '@codemirror/search',
  '@codemirror/state',
  '@codemirror/theme-one-dark',
  '@codemirror/view',
  '@replit/codemirror-',
  '@uiw/codemirror-extensions-basic-setup',
  '@uiw/react-codemirror',
  'crelt',
  'style-mod',
  'w3c-keyname',
];

const EDITOR_LANGUAGE_PACKAGES = [
  '@codemirror/lang-',
  '@codemirror/legacy-modes',
  '@lezer/',
  'codemirror-lang-',
];

const IDE_TREE_PACKAGES = [
  '@react-dnd/',
  'dnd-core',
  'react-arborist',
  'react-dnd',
  'react-dnd-html5-backend',
  'react-window',
  'redux',
];

const MARKDOWN_PACKAGES = [
  'bail',
  'ccount',
  'character-entities',
  'comma-separated-tokens',
  'decode-named-character-reference',
  'devlop',
  'escape-string-regexp',
  'hast-util-',
  'html-url-attributes',
  'is-plain-obj',
  'markdown-table',
  'mdast-util-',
  'micromark',
  'property-information',
  'react-markdown',
  'remark-',
  'space-separated-tokens',
  'trim-lines',
  'trough',
  'unified',
  'unist-util-',
  'vfile',
  'zwitch',
];

function isPackageMatch(id: string, packages: string[]): boolean {
  return packages.some((packageName) => id.includes(packageName));
}

function isCodeMirrorCorePackage(id: string): boolean {
  return id.includes('/node_modules/codemirror/');
}

export default defineConfig({
  plugins: [react()],
  base: '/dashboard/',
  resolve: {
    alias: {
      // Vite 6 sometimes fails to resolve devlop's conditional package export
      // through transitive markdown dependencies during production builds.
      devlop: fileURLToPath(new URL('./node_modules/devlop/lib/default.js', import.meta.url)),
    },
  },
  build: {
    outDir: '../src/main/resources/static/dashboard',
    emptyOutDir: true,
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (!id.includes('node_modules')) {
            return undefined;
          }

          if (isCodeMirrorCorePackage(id) || isPackageMatch(id, EDITOR_BASE_PACKAGES)) {
            return 'code-editor';
          }

          if (isPackageMatch(id, EDITOR_LANGUAGE_PACKAGES)) {
            return undefined;
          }

          if (isPackageMatch(id, IDE_TREE_PACKAGES)) {
            return 'ide-tree';
          }

          if (isPackageMatch(id, MARKDOWN_PACKAGES)) {
            return 'markdown';
          }

          if (id.includes('recharts')) {
            return 'charts';
          }

          if (id.includes('@tanstack/react-query') || id.includes('axios')) {
            return 'data';
          }

          if (
            id.includes('/react/') ||
            id.includes('react-dom') ||
            id.includes('scheduler')
          ) {
            return 'core-react';
          }

          if (id.includes('zustand')) {
            return 'state';
          }

          if (
            id.includes('class-variance-authority') ||
            id.includes('tailwind-merge') ||
            id.includes('react-hot-toast') ||
            id.includes('react-icons')
          ) {
            return 'ui';
          }

          return 'vendor';
        },
      },
    },
  },
  server: {
    proxy: {
      '/api': 'http://localhost:8080',
      '/ws': {
        target: 'ws://localhost:8080',
        ws: true,
      },
    },
  },
});
