// ESLint v9 flat config. Replaces the legacy .eslintrc the `lint` script
// expected (which never existed in this package, so `npm run lint` errored out
// entirely under ESLint 9). Pragmatic ruleset: TypeScript-aware parsing + the
// recommended sets, with the noisiest stylistic rules relaxed to warnings so the
// signal (real bugs: unreachable code, no-undef, etc.) isn't drowned out.
const js = require('@eslint/js');
const tseslint = require('typescript-eslint');

module.exports = tseslint.config(
  {
    // Don't lint build output, deps, native projects, or generated specs.
    ignores: [
      'node_modules/**',
      'android/**',
      'ios/**',
      'coverage/**',
      'dist/**',
      'build/**',
      '.maestro/**',
      'metro.config.js',
      'babel.config.js',
      'jest.config.js',
      'eslint.config.js',
    ],
  },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  {
    files: ['**/*.{ts,tsx}'],
    languageOptions: {
      ecmaVersion: 2022,
      sourceType: 'module',
      globals: {
        console: 'readonly',
        fetch: 'readonly',
        setTimeout: 'readonly',
        clearTimeout: 'readonly',
        setInterval: 'readonly',
        clearInterval: 'readonly',
        __DEV__: 'readonly',
        process: 'readonly',
      },
    },
    rules: {
      // TypeScript's own compiler already flags undefined identifiers, and the
      // core no-undef rule produces false positives on ambient globals/types
      // (global, module, document, etc.). typescript-eslint's docs recommend
      // turning it off for TS files.
      'no-undef': 'off',
      // Allow intentional throwaways prefixed with _ ; warn (don't fail) on the rest.
      '@typescript-eslint/no-unused-vars': [
        'warn',
        { argsIgnorePattern: '^_', varsIgnorePattern: '^_', caughtErrorsIgnorePattern: '^_' },
      ],
      // The engine/network layers use `any` at JSON/protocol boundaries deliberately.
      '@typescript-eslint/no-explicit-any': 'off',
      '@typescript-eslint/no-require-imports': 'off',
      // Stylistic TS rules — surface as warnings, don't fail the build.
      '@typescript-eslint/no-this-alias': 'warn',
      '@typescript-eslint/no-namespace': 'off', // TurboModule codegen uses declare namespace
      '@typescript-eslint/no-empty-object-type': 'warn',
      // Parsers deliberately match control chars / bracket classes.
      'no-control-regex': 'off',
      'no-useless-escape': 'warn',
      'no-empty': ['warn', { allowEmptyCatch: true }],
    },
  },
  {
    // Plain .js files are the RN entry point (index.js) + CommonJS config
    // (react-native.config.js): Node + RN-runtime globals, require() allowed.
    files: ['**/*.js'],
    languageOptions: {
      sourceType: 'commonjs',
      globals: {
        module: 'writable',
        require: 'readonly',
        __dirname: 'readonly',
        __filename: 'readonly',
        process: 'readonly',
        global: 'readonly',
        console: 'readonly',
        document: 'readonly',
      },
    },
    rules: {
      'no-undef': 'off',
      '@typescript-eslint/no-require-imports': 'off',
    },
  },
  {
    // Tests use jest globals and looser typing.
    files: ['__tests__/**/*.{ts,tsx}', '**/*.test.{ts,tsx}'],
    languageOptions: {
      globals: {
        describe: 'readonly',
        it: 'readonly',
        test: 'readonly',
        expect: 'readonly',
        beforeEach: 'readonly',
        afterEach: 'readonly',
        beforeAll: 'readonly',
        afterAll: 'readonly',
        jest: 'readonly',
      },
    },
    rules: {
      '@typescript-eslint/no-non-null-assertion': 'off',
    },
  },
);
