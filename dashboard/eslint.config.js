import js from '@eslint/js';
import globals from 'globals';
import reactHooks from 'eslint-plugin-react-hooks';
import reactRefresh from 'eslint-plugin-react-refresh';
import tseslint from 'typescript-eslint';

export default tseslint.config(
  { ignores: ['dist', 'node_modules', '../src/main/resources/static/dashboard'] },

  // Base: ESLint recommended + TypeScript strict + stylistic
  js.configs.recommended,
  ...tseslint.configs.strictTypeChecked,
  ...tseslint.configs.stylisticTypeChecked,

  // TypeScript parser options
  {
    languageOptions: {
      ecmaVersion: 2020,
      globals: globals.browser,
      parserOptions: {
        projectService: true,
        tsconfigRootDir: import.meta.dirname,
      },
    },
  },

  // React plugins
  {
    plugins: {
      'react-hooks': reactHooks,
      'react-refresh': reactRefresh,
    },
    rules: {
      ...reactHooks.configs.recommended.rules,
      'react-refresh/only-export-components': ['warn', { allowConstantExport: true }],
    },
  },

  // Strict quality rules
  {
    rules: {
      // --- No any ---
      '@typescript-eslint/no-explicit-any': 'off',
      '@typescript-eslint/no-unsafe-assignment': 'off',
      '@typescript-eslint/no-unsafe-call': 'off',
      '@typescript-eslint/no-unsafe-member-access': 'off',
      '@typescript-eslint/no-unsafe-return': 'off',
      '@typescript-eslint/no-unsafe-argument': 'off',

      // --- Strict type discipline ---
      '@typescript-eslint/explicit-function-return-type': ['off', {
        allowExpressions: true,
        allowTypedFunctionExpressions: true,
        allowHigherOrderFunctions: true,
        allowDirectConstAssertionInArrowFunctions: true,
      }],
      '@typescript-eslint/no-unnecessary-type-assertion': 'error',
      '@typescript-eslint/no-unnecessary-condition': 'off',
      '@typescript-eslint/prefer-nullish-coalescing': 'off',
      '@typescript-eslint/prefer-optional-chain': 'error',
      '@typescript-eslint/strict-boolean-expressions': ['off', {
        allowNullableBoolean: true,
        allowNullableString: true,
        allowNullableNumber: false,
      }],
      '@typescript-eslint/switch-exhaustiveness-check': 'error',
      '@typescript-eslint/consistent-type-imports': ['error', { prefer: 'type-imports' }],
      '@typescript-eslint/no-import-type-side-effects': 'error',

      // --- No unused code ---
      '@typescript-eslint/no-unused-vars': ['error', {
        argsIgnorePattern: '^_',
        varsIgnorePattern: '^_',
        caughtErrorsIgnorePattern: '^_',
      }],

      // --- No sloppy patterns ---
      '@typescript-eslint/no-floating-promises': 'off',
      '@typescript-eslint/no-misused-promises': 'off',
      '@typescript-eslint/await-thenable': 'error',
      '@typescript-eslint/require-await': 'error',
      '@typescript-eslint/no-non-null-assertion': 'error',
      '@typescript-eslint/no-confusing-void-expression': ['error', { ignoreArrowShorthand: true }],
      '@typescript-eslint/return-await': 'off',
      '@typescript-eslint/prefer-promise-reject-errors': 'off',
      '@typescript-eslint/restrict-template-expressions': 'off',
      '@typescript-eslint/restrict-plus-operands': 'off',
      '@typescript-eslint/no-base-to-string': 'off',
      '@typescript-eslint/prefer-for-of': 'off',
      '@typescript-eslint/array-type': 'off',
      '@typescript-eslint/no-empty-function': 'off',
      '@typescript-eslint/no-dynamic-delete': 'off',

      // --- Code clarity ---
      'no-console': ['warn', { allow: ['warn', 'error'] }],
      'no-debugger': 'error',
      'no-alert': 'error',
      'no-var': 'error',
      'prefer-const': 'error',
      'no-nested-ternary': 'off',
      'no-param-reassign': 'error',
      'eqeqeq': 'off',
      'curly': ['error', 'all'],
      'no-else-return': ['error', { allowElseIf: false }],
      'no-lonely-if': 'error',
      'no-unneeded-ternary': 'error',
      'prefer-template': 'error',
      'object-shorthand': 'error',
      'no-useless-rename': 'error',
      'no-useless-return': 'error',

      // --- Import discipline ---
      'no-duplicate-imports': 'error',

      // --- Max complexity ---
      'max-depth': ['error', 4],
      'max-params': ['error', 5],
      'max-lines': ['warn', { max: 400, skipBlankLines: true, skipComments: true }],
      'max-lines-per-function': ['warn', { max: 200, skipBlankLines: true, skipComments: true }],
      'complexity': ['warn', 15],
    },
  },

  // Legacy UI modules: keep strict safety rules, relax size/complexity warnings
  {
    files: [
      'src/api/settings.ts',
      'src/components/chat/ChatInput.tsx',
      'src/components/chat/ChatWindow.tsx',
      'src/components/chat/ContextPanel.tsx',
      'src/pages/SettingsPage.tsx',
      'src/pages/SkillsPage.tsx',
      'src/pages/settings/ToolsTab.tsx',
      'src/pages/settings/TelegramTab.tsx',
      'src/pages/settings/LlmProvidersTab.tsx',
    ],
    rules: {
      'max-lines': 'off',
      'max-lines-per-function': 'off',
      'complexity': 'off',
    },
  },

  // Phase 1 hardening: API layer
  {
    files: ['src/api/**/*.ts'],
    rules: {
      '@typescript-eslint/no-explicit-any': 'error',
      '@typescript-eslint/no-unsafe-assignment': 'error',
      '@typescript-eslint/no-unsafe-call': 'error',
      '@typescript-eslint/no-unsafe-member-access': 'error',
      '@typescript-eslint/no-unsafe-return': 'error',
      '@typescript-eslint/no-unsafe-argument': 'error',
      '@typescript-eslint/explicit-function-return-type': ['error', {
        allowExpressions: true,
        allowTypedFunctionExpressions: true,
        allowHigherOrderFunctions: true,
        allowDirectConstAssertionInArrowFunctions: true,
      }],
    },
  },

  // Phase 2 hardening: async safety in hooks/pages
  {
    files: ['src/hooks/**/*.{ts,tsx}', 'src/pages/**/*.{ts,tsx}'],
    rules: {
      '@typescript-eslint/no-floating-promises': 'error',
      '@typescript-eslint/no-misused-promises': 'error',
    },
  },

  // Phase 5 hardening: strict booleans on cleaned files first
  {
    files: [
      'src/hooks/usePrompts.ts',
      'src/hooks/useSessions.ts',
      'src/hooks/useSkills.ts',
      'src/pages/AnalyticsPage.tsx',
      'src/pages/DiagnosticsPage.tsx',
      'src/pages/LoginPage.tsx',
      'src/pages/PromptsPage.tsx',
      'src/pages/SessionsPage.tsx',
      'src/pages/SkillsPage.tsx',
      'src/pages/SettingsPage.tsx',
      'src/components/chat/ChatInput.tsx',
      'src/components/chat/ChatWindow.tsx',
      'src/components/chat/ContextPanel.tsx',
    ],
    rules: {
      '@typescript-eslint/strict-boolean-expressions': ['error', {
        allowString: false,
        allowNumber: false,
        allowNullableObject: false,
      }],
    },
  },

  // Phase 3 hardening: explicit boundaries in hooks
  {
    files: ['src/hooks/**/*.{ts,tsx}'],
    rules: {
      '@typescript-eslint/explicit-function-return-type': ['error', {
        allowExpressions: true,
        allowTypedFunctionExpressions: true,
        allowHigherOrderFunctions: true,
        allowDirectConstAssertionInArrowFunctions: true,
      }],
    },
  },

  // Phase 4 hardening: explicit boundaries in pages
  {
    files: ['src/pages/**/*.{ts,tsx}'],
    rules: {
      '@typescript-eslint/explicit-function-return-type': ['error', {
        allowExpressions: true,
        allowTypedFunctionExpressions: true,
        allowHigherOrderFunctions: true,
        allowDirectConstAssertionInArrowFunctions: true,
      }],
      '@typescript-eslint/no-explicit-any': 'error',
      '@typescript-eslint/no-unsafe-assignment': 'error',
      '@typescript-eslint/no-unsafe-call': 'error',
      '@typescript-eslint/no-unsafe-member-access': 'error',
      '@typescript-eslint/no-unsafe-return': 'error',
      '@typescript-eslint/no-unsafe-argument': 'error',
    },
  },

);
